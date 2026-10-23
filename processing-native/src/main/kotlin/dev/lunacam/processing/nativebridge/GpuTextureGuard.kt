package dev.lunacam.processing.nativebridge

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface GpuTextureResult {
    /** Caller owns this separate bitmap and must recycle it. Source is never modified. */
    data class Success(
        val bitmap: Bitmap,
        val changedPixels: Long,
        val totalNs: Long,
        val stagesNs: Map<String, Long>,
        /** Combined denoise + sharpen count; changedPixels remains legacy TextureGuard only. */
        val detailChangedPixels: Long = 0
    ) : GpuTextureResult

    data class Unavailable(val reason: String, val totalNs: Long) : GpuTextureResult
}

/**
 * GLES 3.1 TextureGuard with exact integer equivalents of the CPU's bounded filter.
 * Each call creates and destroys its own context on the caller's non-UI thread. No service,
 * worker thread, model, persistent GPU allocation, or dependency is installed.
 * Total timing includes EGL/shader setup, bitmap upload, dispatch, readback and EGL cleanup.
 * A one-second GPU fence wait bounds that wait, not potentially blocking vendor driver calls.
 */
object GpuTextureGuard {
    @JvmOverloads
    fun process(input: Bitmap, detailEnabled: Boolean = false): GpuTextureResult {
        val start = now()
        fun unavailable(reason: String) = GpuTextureResult.Unavailable(reason, now() - start)
        if (Looper.myLooper() == Looper.getMainLooper()) return unavailable("main_thread")
        if (input.isRecycled || input.config != Bitmap.Config.ARGB_8888) {
            return unavailable("requires_argb8888")
        }
        if (input.width.toLong() * input.height !in 1..16_000_000L) {
            return unavailable("pixel_limit")
        }
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            return unavailable("requires_little_endian_rgba")
        }
        // Never replace a context owned by the caller (for example an existing renderer).
        if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
            return unavailable("caller_has_egl_context")
        }
        val stages = linkedMapOf<String, Long>()
        val gl = Resources()
        var output: Bitmap? = null
        var changed = 0L
        var detailChanged = 0L
        var failure: String? = null
        try {
            var phase = now()
            gl.open(detailEnabled)
            stages["setup"] = now() - phase
            val groupsX = (input.width + 15) / 16
            val groupsY = (input.height + 7) / 8
            val target = Bitmap.createBitmap(
                input.width,
                input.height,
                Bitmap.Config.ARGB_8888,
                input.hasAlpha(),
                input.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            ).also {
                it.setPremultiplied(input.isPremultiplied)
                it.density = input.density
            }
            output = target
            val inputBytes = input.rowBytes.toLong() * input.height
            val outputBytes = target.rowBytes.toLong() * target.height
            val groupCount = groupsX * groupsY
            val countsBytes = groupCount.toLong() * 4 * (if (detailEnabled) 2 else 1)
            // Pass two returns to buffer zero using target stride, which may exceed source stride.
            val bufferZeroBytes = if (detailEnabled) maxOf(inputBytes, outputBytes) else inputBytes
            check(input.rowBytes % 4 == 0 && target.rowBytes % 4 == 0) { "unaligned_stride" }
            gl.checkLimits(groupsX, groupsY, maxOf(bufferZeroBytes, outputBytes, countsBytes))

            phase = now()
            GLES31.glGenBuffers(3, gl.buffers, 0)
            gl.bindBuffer(0, bufferZeroBytes)
            gl.mapped(inputBytes, GLES31.GL_MAP_WRITE_BIT or GLES31.GL_MAP_INVALIDATE_BUFFER_BIT) {
                input.copyPixelsToBuffer(it)
            }
            gl.bindBuffer(1, outputBytes)
            gl.bindBuffer(2, countsBytes)
            gl.checkError("buffer_upload")
            stages["uploadAndAllocation"] = now() - phase

            phase = now()
            val textureSubmit = now()
            GLES31.glUseProgram(gl.program)
            gl.uniform("width", input.width)
            gl.uniform("height", input.height)
            gl.uniform("inputStride", input.rowBytes / 4)
            gl.uniform("outputStride", target.rowBytes / 4)
            GLES31.glDispatchCompute(groupsX, groupsY, 1)
            GLES31.glMemoryBarrier(
                GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
            )
            gl.checkError("dispatch")
            stages["textureDispatchSubmit"] = now() - textureSubmit
            if (detailEnabled) {
                val detailSubmit = now()
                GLES31.glUseProgram(gl.detailProgram)
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, gl.buffers[1])
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, gl.buffers[0])
                gl.uniform("width", input.width, gl.detailProgram)
                gl.uniform("height", input.height, gl.detailProgram)
                gl.uniform("inputStride", target.rowBytes / 4, gl.detailProgram)
                gl.uniform("outputStride", target.rowBytes / 4, gl.detailProgram)
                gl.uniform("countsOffset", groupCount, gl.detailProgram)
                GLES31.glDispatchCompute(groupsX, groupsY, 1)
                GLES31.glMemoryBarrier(
                    GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
                )
                gl.checkError("detail_dispatch")
                stages["detailDispatchSubmit"] = now() - detailSubmit
            }
            gl.fence = GLES31.glFenceSync(GLES31.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(gl.fence != 0L) { "fence_creation" }
            when (
                GLES31.glClientWaitSync(
                    gl.fence,
                    GLES31.GL_SYNC_FLUSH_COMMANDS_BIT,
                    1_000_000_000L
                )
            ) {
                GLES31.GL_ALREADY_SIGNALED, GLES31.GL_CONDITION_SATISFIED -> Unit
                GLES31.GL_TIMEOUT_EXPIRED -> error("gpu_fence_timeout")
                else -> error("gpu_fence_failed")
            }
            stages["dispatchAndWait"] = now() - phase

            phase = now()
            GLES31.glBindBuffer(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                gl.buffers[if (detailEnabled) 0 else 1]
            )
            gl.mapped(outputBytes, GLES31.GL_MAP_READ_BIT) { target.copyPixelsFromBuffer(it) }
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gl.buffers[2])
            gl.mapped(countsBytes, GLES31.GL_MAP_READ_BIT) {
                val counts = it.asIntBuffer()
                repeat(groupCount) {
                    val count = counts.get()
                    check(count in 0..128) { "invalid_group_count" }
                    changed += count
                }
                if (detailEnabled) {
                    repeat(groupCount) {
                        val count = counts.get()
                        check(count in 0..128) { "invalid_detail_group_count" }
                        detailChanged += count
                    }
                }
            }
            gl.checkError("readback")
            stages["readbackAndBitmap"] = now() - phase
        } catch (error: RuntimeException) {
            failure = error.message ?: error.javaClass.simpleName
        } catch (_: OutOfMemoryError) {
            failure = "allocation_failed"
        } finally {
            val phase = now()
            try {
                gl.close()
            } catch (error: RuntimeException) {
                failure = failure ?: "cleanup_${error.javaClass.simpleName}"
            }
            stages["cleanup"] = now() - phase
        }
        if (failure != null || output == null) {
            output?.recycle()
            return unavailable(failure ?: "missing_output")
        }
        return GpuTextureResult.Success(
            output,
            changed,
            now() - start,
            stages.toMap(),
            detailChanged
        )
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    private class Resources {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var initialized = false
        private var current = false
        var program = 0
        var detailProgram = 0
        var fence = 0L
        val buffers = IntArray(3)

        fun open(detailEnabled: Boolean) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "egl_display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "egl_initialize" }
            initialized = true
            val attributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
                "egl_choose_config"
            }
            check(count[0] > 0) { "gles3_config_unavailable" }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "egl_create_context" }
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "egl_pbuffer" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "egl_make_current" }
            current = true
            val major = integer(GLES31.GL_MAJOR_VERSION)
            val minor = integer(GLES31.GL_MINOR_VERSION)
            check(major > 3 || (major == 3 && minor >= 1)) { "gles31_unavailable" }
            program = compileProgram(SHADER)
            if (detailEnabled) detailProgram = compileProgram(DETAIL_SHADER)
            checkError("setup")
        }

        private fun compileProgram(source: String): Int {
            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            check(shader != 0) { "shader_creation" }
            var candidate = 0
            try {
                GLES31.glShaderSource(shader, source)
                GLES31.glCompileShader(shader)
                val status = IntArray(1)
                GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "shader_compile:${GLES31.glGetShaderInfoLog(shader)}" }
                candidate = GLES31.glCreateProgram()
                check(candidate != 0) { "program_creation" }
                GLES31.glAttachShader(candidate, shader)
                GLES31.glLinkProgram(candidate)
                GLES31.glGetProgramiv(candidate, GLES31.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "program_link:${GLES31.glGetProgramInfoLog(candidate)}" }
                return candidate
            } catch (failure: Throwable) {
                if (candidate != 0) GLES31.glDeleteProgram(candidate)
                throw failure
            } finally {
                GLES31.glDeleteShader(shader)
            }
        }

        fun checkLimits(groupsX: Int, groupsY: Int, maxBytes: Long) {
            check(maxBytes in 1..Int.MAX_VALUE.toLong()) { "buffer_size_overflow" }
            val maxBlock = LongArray(1)
            GLES31.glGetInteger64v(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, maxBlock, 0)
            check(maxBytes <= maxBlock[0]) { "ssbo_size_limit" }
            check(integer(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS) >= 128) {
                "workgroup_invocation_limit"
            }
            check(integer(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS) >= 3) { "ssbo_count_limit" }
            for ((axis, size) in listOf(0 to groupsX, 1 to groupsY)) {
                val maximum = IntArray(1)
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis, maximum, 0)
                check(size <= maximum[0]) { "workgroup_count_limit" }
                GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis, maximum, 0)
                check((if (axis == 0) 16 else 8) <= maximum[0]) { "workgroup_axis_limit" }
            }
            checkError("limits")
        }

        fun bindBuffer(index: Int, bytes: Long) {
            check(buffers[index] != 0) { "buffer_creation" }
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                bytes.toInt(),
                null,
                GLES31.GL_STATIC_COPY
            )
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, buffers[index])
        }

        fun mapped(bytes: Long, access: Int, block: (ByteBuffer) -> Unit) {
            val mapped = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0,
                bytes.toInt(),
                access
            ) as? ByteBuffer ?: error("buffer_map_failed")
            var unmapped = false
            try {
                mapped.order(ByteOrder.nativeOrder())
                mapped.position(0)
                mapped.limit(bytes.toInt())
                block(mapped)
            } finally {
                unmapped = GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            }
            check(unmapped) { "buffer_contents_lost" }
        }

        fun uniform(name: String, value: Int, activeProgram: Int = program) {
            val location = GLES31.glGetUniformLocation(activeProgram, name)
            check(location >= 0) { "missing_uniform_$name" }
            GLES31.glUniform1i(location, value)
        }

        fun checkError(stage: String) {
            val error = GLES31.glGetError()
            check(error == GLES31.GL_NO_ERROR) { "gl_${stage}_$error" }
        }

        private fun integer(key: Int): Int = IntArray(1).also {
            GLES31.glGetIntegerv(key, it, 0)
        }[0]

        fun close() {
            var failed = false
            if (current) {
                if (fence != 0L) GLES31.glDeleteSync(fence)
                GLES31.glDeleteBuffers(buffers.size, buffers, 0)
                if (program != 0) GLES31.glDeleteProgram(program)
                if (detailProgram != 0) GLES31.glDeleteProgram(detailProgram)
                if (!EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                ) {
                    failed = true
                }
            }
            if (surface != EGL14.EGL_NO_SURFACE && !EGL14.eglDestroySurface(display, surface)) {
                failed = true
            }
            if (context != EGL14.EGL_NO_CONTEXT && !EGL14.eglDestroyContext(display, context)) {
                failed = true
            }
            // Android reference-counts EGL display initialization across users/threads.
            if (initialized && !EGL14.eglTerminate(display)) failed = true
            check(!failed) { "egl_cleanup_failed" }
        }
    }

    // Detail Guard 0.1: every decision reads the immutable completed TextureGuard output.
    // This pass has no floating-point math; signed rational ties round away from zero.
    private val DETAIL_SHADER: String by lazy {
        """
        #version 310 es
        precision highp int;
        layout(local_size_x = 16, local_size_y = 8) in;
        layout(std430, binding = 0) readonly buffer Source { uint sourcePixels[]; };
        layout(std430, binding = 1) writeonly buffer Output { uint outputPixels[]; };
        layout(std430, binding = 2) writeonly buffer Counts { uint groupCounts[]; };
        uniform int width;
        uniform int height;
        uniform int inputStride;
        uniform int outputStride;
        uniform int countsOffset;
        shared uint changed[128];
        const ivec2 directions[4] = ivec2[4](
            ivec2(-1, 0), ivec2(0, -1), ivec2(-1, -1), ivec2(1, -1)
        );
        ivec4 rgba(uint p) {
            return ivec4(int(p & 255u), int((p >> 8) & 255u),
                         int((p >> 16) & 255u), int(p >> 24));
        }
        ivec4 sampleAt(ivec2 at) {
            return rgba(sourcePixels[at.y * inputStride + at.x]);
        }
        ivec3 yc(ivec4 p) {
            int y = p.r + 2 * p.g + p.b;
            return ivec3(y, p.r - p.b, 4 * p.g - y);
        }
        int roundAway(int n, int denominator) {
            return n < 0 ? -((-n + denominator / 2) / denominator) :
                (n + denominator / 2) / denominator;
        }
        uint filtered(ivec2 at, uint pixel) {
            if (at.x < 2 || at.y < 2 || at.x + 2 >= width || at.y + 2 >= height) {
                return pixel;
            }
            ivec4 p = rgba(pixel);
            ivec3 center = yc(p);
            if (p.a != 255 || center.x < 80 || center.x > 940) return pixel;
            int lo = center.x, hi = center.x;
            int votes = 0, sumY = 0, minPair = 2048, maxPair = -2048;
            int bestGradient = 0, bestDetail = 0;
            bool coherentLine = false;
            for (int i = 0; i < 4; ++i) {
                ivec2 d = directions[i];
                ivec4 pa = sampleAt(at - d), pb = sampleAt(at + d);
                ivec4 pA = sampleAt(at - 2 * d), pB = sampleAt(at + 2 * d);
                if (pa.a != 255 || pb.a != 255 || pA.a != 255 || pB.a != 255) return pixel;
                ivec3 a = yc(pa), b = yc(pb);
                int YA = yc(pA).x, YB = yc(pB).x;
                coherentLine = coherentLine ||
                    (abs(a.x - center.x) <= 4 && abs(YA - center.x) <= 4) ||
                    (abs(b.x - center.x) <= 4 && abs(YB - center.x) <= 4);
                lo = min(lo, min(a.x, b.x));
                hi = max(hi, max(a.x, b.x));
                if (abs(a.x - b.x) <= 16 && abs(a.y - b.y) <= 12 && abs(a.z - b.z) <= 24) {
                    int pairY = a.x + b.x;
                    ++votes; sumY += pairY;
                    minPair = min(minPair, pairY); maxPair = max(maxPair, pairY);
                }
                int g1 = b.x - a.x, g2 = YB - YA;
                int d1 = 2 * center.x - a.x - b.x;
                int d2 = 2 * center.x - YA - YB;
                if (g1 * g2 > 0 && abs(g1) >= 32 && abs(g2) >= abs(g1) &&
                    abs(g2) <= 4 * abs(g1) && d1 * d2 > 0 && abs(d2) >= abs(d1) &&
                    abs(d1) >= 8 && abs(g1) > bestGradient) {
                    // A real edge must recur on both adjacent parallel scan lines.
                    ivec2 n = ivec2(-d.y, d.x);
                    ivec4 q1 = sampleAt(at + n + d), q2 = sampleAt(at + n - d);
                    ivec4 q3 = sampleAt(at - n + d), q4 = sampleAt(at - n - d);
                    if (q1.a == 255 && q2.a == 255 && q3.a == 255 && q4.a == 255) {
                        int h1 = yc(q1).x - yc(q2).x;
                        int h2 = yc(q3).x - yc(q4).x;
                        if (abs(h1 - g1) <= 8 && abs(h2 - g1) <= 8) {
                            bestGradient = abs(g1); bestDetail = d1;
                        }
                    }
                }
            }
            int delta = 0;
            if (hi - lo <= 48) {
                // Weak texture and recurring lines cannot safely be called random noise.
                if (center.x == lo || center.x == hi || hi - lo <= 20 ||
                    (hi - lo >= 8 && coherentLine)) return pixel;
                if (votes >= 3 && maxPair - minPair <= 24) {
                    int n = sumY - 2 * votes * center.x;
                    if (abs(n) <= 48 * votes) {
                        delta = clamp(roundAway(7 * n, 160 * votes), -2, 2);
                    }
                }
            } else if (bestGradient != 0) {
                delta = clamp(roundAway(bestDetail, 32), -2, 2);
                // lo <= center <= hi: truncation is ceil for the negative lower bound.
                delta = clamp(delta, (lo - center.x) / 4, (hi - center.x) / 4);
            }
            int minimum = min(p.r, min(p.g, p.b));
            int maximum = max(p.r, max(p.g, p.b));
            delta = clamp(delta, -minimum, 255 - maximum);
            ivec3 color = p.rgb + ivec3(delta);
            return uint(color.r) | (uint(color.g) << 8) | (uint(color.b) << 16) |
                (pixel & 0xff000000u);
        }
        void main() {
            ivec2 at = ivec2(gl_GlobalInvocationID.xy);
            uint local = gl_LocalInvocationIndex;
            uint count = 0u;
            if (at.x < width && at.y < height) {
                uint before = sourcePixels[at.y * inputStride + at.x];
                uint after = filtered(at, before);
                outputPixels[at.y * outputStride + at.x] = after;
                count = before != after ? 1u : 0u;
            }
            changed[local] = count;
            barrier();
            for (uint step = 64u; step > 0u; step >>= 1u) {
                if (local < step) changed[local] += changed[local + step];
                barrier();
            }
            if (local == 0u) {
                uint group = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
                groupCounts[uint(countsOffset) + group] = changed[0];
            }
        }
        """.trimIndent()
    }

    private val SHADER = """
        #version 310 es
        precision highp int;
        layout(local_size_x = 16, local_size_y = 8) in;
        layout(std430, binding = 0) readonly buffer Source { uint sourcePixels[]; };
        layout(std430, binding = 1) writeonly buffer Output { uint outputPixels[]; };
        layout(std430, binding = 2) writeonly buffer Counts { uint groupCounts[]; };
        uniform int width;
        uniform int height;
        uniform int inputStride;
        uniform int outputStride;
        shared uint changed[128];
        const ivec2 directions[4] = ivec2[4](
            ivec2(-1, 0), ivec2(0, -1), ivec2(-1, -1), ivec2(1, -1)
        );
        ivec4 rgba(uint p) {
            return ivec4(int(p & 255u), int((p >> 8) & 255u),
                         int((p >> 16) & 255u), int(p >> 24));
        }
        ivec3 yc(ivec4 p) {
            int y = p.r + 2 * p.g + p.b;
            return ivec3(y, p.r - p.b, 4 * p.g - y);
        }
        int roundedDelta(int n, int d, int cap) {
            // Exact rational form of floor(residual * .35f + .5). Host test exhausts
            // every possible sum/center/vote combination, including negative ties.
            int a = 7 * n + 10 * d;
            int b = 20 * d;
            int q = a >= 0 ? a / b : -((-a + b - 1) / b);
            return clamp(q, -cap, cap);
        }
        uint filtered(ivec2 at, uint pixel) {
            if (at.x == 0 || at.y == 0 || at.x + 1 >= width || at.y + 1 >= height) {
                return pixel;
            }
            ivec4 p = rgba(pixel);
            ivec3 c = yc(p);
            if (p.a != 255 || c.x < 64 || c.x > 956) return pixel;
            int minY = c.x, maxY = c.x;
            int minCo = 2048, maxCo = -2048, minCg = 4096, maxCg = -4096;
            int sumCo = 0, sumCg = 0, votes = 0;
            for (int i = 0; i < 4; ++i) {
                ivec2 a = at + directions[i];
                ivec2 b = at - directions[i];
                ivec4 left = rgba(sourcePixels[a.y * inputStride + a.x]);
                ivec4 right = rgba(sourcePixels[b.y * inputStride + b.x]);
                if (left.a != 255 || right.a != 255) return pixel;
                ivec3 l = yc(left), r = yc(right);
                minY = min(minY, min(l.x, r.x));
                maxY = max(maxY, max(l.x, r.x));
                if (maxY - minY > 40) return pixel;
                if (abs(l.x - r.x) <= 24 && abs(l.y - r.y) <= 12 && abs(l.z - r.z) <= 24) {
                    int co = l.y + r.y, cg = l.z + r.z;
                    minCo = min(minCo, co); maxCo = max(maxCo, co);
                    minCg = min(minCg, cg); maxCg = max(maxCg, cg);
                    sumCo += co; sumCg += cg; ++votes;
                }
            }
            if (votes < 3 || maxCo - minCo > 16 || maxCg - minCg > 24) return pixel;
            int nCo = sumCo - 2 * votes * c.y;
            int nCg = sumCg - 2 * votes * c.z;
            int dCo = 4 * votes, dCg = 8 * votes;
            if (abs(nCo) > 12 * dCo || abs(nCg) > 12 * dCg) return pixel;
            int dc = roundedDelta(nCo, dCo, 2), dg = roundedDelta(nCg, dCg, 1);
            ivec3 outColor = p.rgb + ivec3(dc - dg, dg, -dc - dg);
            if (any(lessThan(outColor, ivec3(0))) || any(greaterThan(outColor, ivec3(255)))) {
                return pixel;
            }
            return uint(outColor.r) | (uint(outColor.g) << 8) | (uint(outColor.b) << 16) |
                (pixel & 0xff000000u);
        }
        void main() {
            ivec2 at = ivec2(gl_GlobalInvocationID.xy);
            uint local = gl_LocalInvocationIndex;
            uint count = 0u;
            if (at.x < width && at.y < height) {
                uint before = sourcePixels[at.y * inputStride + at.x];
                uint after = filtered(at, before);
                outputPixels[at.y * outputStride + at.x] = after;
                count = before != after ? 1u : 0u;
            }
            changed[local] = count;
            barrier();
            for (uint step = 64u; step > 0u; step >>= 1u) {
                if (local < step) changed[local] += changed[local + step];
                barrier();
            }
            if (local == 0u) {
                groupCounts[gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x] = changed[0];
            }
        }
    """.trimIndent()
}
