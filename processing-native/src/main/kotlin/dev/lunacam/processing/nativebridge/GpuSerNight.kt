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

data class SerNightStats(val changedPixels: Long, val liftedCells: Long, val elapsedMs: Long)

sealed interface GpuSerNightResult {
    /** Caller owns bitmap and optional diagnostic packed RAW/EV buffer. Inputs are unchanged. */
    data class Success(
        val bitmap: Bitmap,
        val stats: SerNightStats,
        val stagesNs: Map<String, Long>,
        val totalNs: Long,
        val packedPixels: ByteBuffer? = null
    ) : GpuSerNightResult
    data class Unavailable(val reason: String, val totalNs: Long) : GpuSerNightResult
}

/** Three bounded compute passes; one RAW upload, one RGB readback, no persistent worker/context. */
object GpuSerNight {
    fun process(
        width: Int,
        height: Int,
        frames: List<RawFusionFrame>,
        plan: SerNightPlan,
        color: SerNightColor,
        enabled: Boolean,
        readbackPacked: Boolean = false
    ): GpuSerNightResult {
        val start = now()
        fun unavailable(reason: String) = GpuSerNightResult.Unavailable(reason, now() - start)
        if (Looper.myLooper() == Looper.getMainLooper()) return unavailable("main_thread")
        if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
            return unavailable("caller_has_egl_context")
        }
        val gl = Resources()
        val stages = linkedMapOf<String, Long>()
        var bitmap: Bitmap? = null
        var packed: ByteBuffer? = null
        var failure: String? = null
        var changed = 0L
        var lifted = 0L
        try {
            require(width >= 64 && height >= 64 && width % 2 == 0 && height % 2 == 0)
            require(width.toLong() * height <= 16_000_000)
            require(frames.size == 1 || frames.size == 3)
            require(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            val count = width * height
            val rawBytes = count * 2
            val packedBytes = count * 4L
            val metadata = FloatArray(42)
            frames.forEachIndexed { index, frame ->
                require(frame.pixels.isDirect && frame.pixels.order() == ByteOrder.nativeOrder())
                require(frame.pixels.position() == 0 && frame.pixels.remaining() == rawBytes)
                require(frame.pixels.capacity() == rawBytes)
                val m = frame.metadata
                require(
                    m.blackLevels.size == 4 && m.noiseSlope.size == 4 &&
                        m.noiseIntercept.size == 4
                )
                require(m.whiteLevel.isFinite() && m.whiteLevel in 64.0..65535.0)
                require(m.exposureScale.isFinite() && m.exposureScale in 0.5..2.0)
                for (c in 0..3) {
                    require(
                        m.blackLevels[c].isFinite() && m.blackLevels[c] >= 0 &&
                            m.whiteLevel - m.blackLevels[c] >= 32
                    )
                    require(m.noiseSlope[c].isFinite() && m.noiseSlope[c] in 0.0..0.1)
                    require(m.noiseIntercept[c].isFinite() && m.noiseIntercept[c] in 0.0..0.1)
                    metadata[index * 14 + c] = m.blackLevels[c].toFloat()
                    metadata[index * 14 + 6 + c] = m.noiseSlope[c].toFloat()
                    metadata[index * 14 + 10 + c] = m.noiseIntercept[c].toFloat()
                }
                metadata[index * 14 + 4] = m.whiteLevel.toFloat()
                metadata[index * 14 + 5] = m.exposureScale.toFloat()
            }
            require(plan.baselineIndex in frames.indices && plan.shifts.size == 6)
            require(plan.shifts.all { it % 2 == 0 && it in -64..64 })
            require(
                plan.shifts[plan.baselineIndex * 2] == 0 &&
                    plan.shifts[plan.baselineIndex * 2 + 1] == 0
            )
            require(plan.acceptedMask and (1 shl plan.baselineIndex) != 0)
            require(plan.acceptedMask and ((1 shl frames.size) - 1).inv() == 0)
            require(
                plan.tileSize == 64 && plan.tileColumns == (width + 63) / 64 &&
                    plan.tileRows == (height + 63) / 64
            )
            require(plan.tileBounds.size == plan.tileColumns * plan.tileRows * 4)
            require(
                plan.activeArea.size == 4 && plan.activeArea[0] >= 0 &&
                    plan.activeArea[1] >= 0 &&
                    plan.activeArea[2] <= width && plan.activeArea[3] <= height &&
                    plan.activeArea[2] > plan.activeArea[0] &&
                    plan.activeArea[3] > plan.activeArea[1]
            )
            val colors = color.validated()
            val shading = color.shading
            shading?.validate(width, height)
            val baseMetadata = frames[plan.baselineIndex].metadata
            val matchingBlackLevels = color.blackLevels.indices.all {
                color.blackLevels[it] == baseMetadata.blackLevels[it].toFloat()
            }
            require(matchingBlackLevels && color.whiteLevel == baseMetadata.whiteLevel.toFloat())
            var phase = now()
            gl.open()
            stages["setup"] = now() - phase
            val gx = (width / 2 + 15) / 16
            val gy = (height / 2 + 7) / 8
            val groups = gx * gy
            val groupsRenderX = (width + 15) / 16
            val groupsRenderY = (height + 7) / 8
            val target = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                false,
                ColorSpace.get(ColorSpace.Named.SRGB)
            ).also { bitmap = it }
            require(target.rowBytes == width * 4) { "unsupported_bitmap_stride" }
            val sourceBytes = maxOf(rawBytes.toLong() * frames.size, packedBytes)
            val tileBytes = plan.tileBounds.size * 4L
            val countBytes = groups * 8L
            gl.checkLimits(groupsRenderX, groupsRenderY, sourceBytes)
            phase = now()
            GLES31.glGenBuffers(gl.buffers.size, gl.buffers, 0)
            gl.bindBuffer(0, sourceBytes)
            gl.mapped(
                rawBytes.toLong() * frames.size,
                GLES31.GL_MAP_WRITE_BIT or GLES31.GL_MAP_INVALIDATE_BUFFER_BIT
            ) { destination ->
                frames.forEach { destination.put(it.pixels.duplicate()) }
            }
            gl.bindBuffer(1, packedBytes)
            gl.bindBuffer(2, tileBytes)
            gl.mapped(tileBytes, GLES31.GL_MAP_WRITE_BIT or GLES31.GL_MAP_INVALIDATE_BUFFER_BIT) {
                it.asFloatBuffer().put(plan.tileBounds)
            }
            gl.bindBuffer(3, countBytes)
            gl.bindBuffer(4, packedBytes)
            val shadingValues = shading?.gains ?: floatArrayOf(1f, 1f, 1f, 1f)
            val shadingBytes = shadingValues.size * 4L
            gl.bindBuffer(5, shadingBytes)
            gl.mapped(
                shadingBytes,
                GLES31.GL_MAP_WRITE_BIT or GLES31.GL_MAP_INVALIDATE_BUFFER_BIT
            ) {
                it.asFloatBuffer().put(shadingValues)
            }
            stages["uploadAndAllocation"] = now() - phase
            phase = now()
            GLES31.glUseProgram(gl.program)
            gl.uniform("width", width)
            gl.uniform("height", height)
            gl.uniform("baseline", plan.baselineIndex)
            gl.uniform("acceptedMask", if (enabled) plan.acceptedMask else 1 shl plan.baselineIndex)
            gl.uniform("tileColumns", plan.tileColumns)
            GLES31.glUniform1fv(
                GLES31.glGetUniformLocation(gl.program, "metadata[0]"),
                42,
                metadata,
                0
            )
            GLES31.glUniform2iv(
                GLES31.glGetUniformLocation(gl.program, "shifts[0]"),
                3,
                plan.shifts,
                0
            )
            GLES31.glUniform4iv(
                GLES31.glGetUniformLocation(gl.program, "activeArea"),
                1,
                plan.activeArea,
                0
            )
            GLES31.glDispatchCompute(gx, gy, 1)
            barrier()
            stages["fusionSubmit"] = now() - phase
            phase = now()
            GLES31.glUseProgram(gl.finalizeProgram)
            gl.uniform("width", width, gl.finalizeProgram)
            gl.uniform("height", height, gl.finalizeProgram)
            gl.uniform("baseline", plan.baselineIndex, gl.finalizeProgram)
            GLES31.glDispatchCompute(gx, gy, 1)
            barrier()
            stages["maskSubmit"] = now() - phase
            phase = now()
            GLES31.glUseProgram(gl.renderProgram)
            gl.uniform("width", width, gl.renderProgram)
            gl.uniform("height", height, gl.renderProgram)
            gl.uniform("cfa", color.cfa, gl.renderProgram)
            gl.uniform("shadingColumns", shading?.columns ?: 0, gl.renderProgram)
            gl.uniform("shadingRows", shading?.rows ?: 0, gl.renderProgram)
            GLES31.glUniform4iv(
                GLES31.glGetUniformLocation(gl.renderProgram, "shadingArea"),
                1,
                shading?.activeArea ?: intArrayOf(0, 0, width, height),
                0
            )
            GLES31.glUniform1fv(
                GLES31.glGetUniformLocation(gl.renderProgram, "color[0]"),
                18,
                colors,
                0
            )
            GLES31.glDispatchCompute(groupsRenderX, groupsRenderY, 1)
            barrier()
            gl.checkError("dispatch")
            stages["renderSubmit"] = now() - phase
            phase = now()
            gl.fence = GLES31.glFenceSync(GLES31.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(gl.fence != 0L) { "fence_creation" }
            val wait = GLES31.glClientWaitSync(
                gl.fence,
                GLES31.GL_SYNC_FLUSH_COMMANDS_BIT,
                2_000_000_000L
            )
            check(wait == GLES31.GL_ALREADY_SIGNALED || wait == GLES31.GL_CONDITION_SATISFIED) {
                "gpu_fence_failed_$wait"
            }
            GLES31.glFinish()
            stages["completionWait"] = now() - phase
            phase = now()
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gl.buffers[0])
            gl.mapped(packedBytes, GLES31.GL_MAP_READ_BIT) { target.copyPixelsFromBuffer(it) }
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gl.buffers[3])
            gl.mapped(countBytes, GLES31.GL_MAP_READ_BIT) {
                val counts = it.asIntBuffer()
                repeat(groups) {
                    val n = counts.get()
                    val cells = counts.get()
                    check(n in 0..512 && cells in 0..128) { "invalid_group_statistics" }
                    changed += n
                    lifted += cells
                }
            }
            if (readbackPacked) {
                val diagnostic = ByteBuffer.allocateDirect(packedBytes.toInt())
                    .order(ByteOrder.nativeOrder())
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, gl.buffers[4])
                gl.mapped(packedBytes, GLES31.GL_MAP_READ_BIT) { diagnostic.put(it) }
                diagnostic.rewind()
                packed = diagnostic
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
            } catch (_: RuntimeException) {
                failure = failure ?: "cleanup_failed"
            }
            stages["cleanup"] = now() - phase
        }
        if (failure != null || bitmap == null) {
            bitmap?.recycle()
            return unavailable(failure ?: "missing_output")
        }
        val elapsed = now() - start
        return GpuSerNightResult.Success(
            bitmap,
            SerNightStats(changed, lifted, elapsed / 1_000_000),
            stages.toMap(),
            elapsed,
            packed
        )
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun barrier() = GLES31.glMemoryBarrier(
        GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
    )

    private class Resources {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var initialized = false
        private var current = false
        var program = 0
        var finalizeProgram = 0
        var renderProgram = 0
        var fence = 0L
        val buffers = IntArray(6)

        fun open() {
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
            program = compileProgram(FUSION_SHADER)
            finalizeProgram = compileProgram(FINALIZE_SHADER)
            renderProgram = compileProgram(RENDER_SHADER)
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
            check(integer(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS) >= 5) { "ssbo_count_limit" }
            check(integer(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) >= 6) {
                "ssbo_binding_limit"
            }
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
                if (finalizeProgram != 0) GLES31.glDeleteProgram(finalizeProgram)
                if (renderProgram != 0) GLES31.glDeleteProgram(renderProgram)
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
            if (!EGL14.eglReleaseThread()) failed = true
            check(!failed) { "egl_cleanup_failed" }
        }
    }

    private val FUSION_SHADER = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x=16, local_size_y=8) in;
        layout(std430,binding=0) readonly buffer Sources { uint rawWords[]; };
        layout(std430,binding=1) writeonly buffer Temporary { uint preliminary[]; };
        layout(std430,binding=2) readonly buffer Bounds { float tileBounds[]; };
        uniform int width, height, baseline, acceptedMask, tileColumns;
        uniform float metadata[42];
        uniform ivec2 shifts[3];
        uniform ivec4 activeArea;
        uint rawAt(int f, int p) {
            uint word=rawWords[f*(width*height/2)+p/2];
            return (word >> uint((p&1)*16)) & 65535u;
        }
        float signalAt(int f, int p, int c) {
            int o=f*14;
            return (float(rawAt(f,p))-metadata[o+c]) /
                (metadata[o+4]-metadata[o+c]) / metadata[o+5];
        }
        float varianceAt(int f, float s, int c) {
            int o=f*14;
            float range=metadata[o+4]-metadata[o+c], e=metadata[o+5];
            float variance=metadata[o+6+c]*max(s*e,0.0)+metadata[o+10+c];
            return max(variance,1.0/(range*range))/(e*e);
        }
        bool usable(int f,int p,int c) {
            int o=f*14; float v=float(rawAt(f,p)), black=metadata[o+c];
            return v>black+1.0 && v<metadata[o+4]-max(4.0,(metadata[o+4]-black)*0.005);
        }
        bool inside(ivec2 at) {
            return at.x>=activeArea.x+4 && at.y>=activeArea.y+4 &&
                at.x+5<activeArea.z && at.y+5<activeArea.w;
        }
        bool matches(int f,int p,int q) {
            for(int c=0;c<4;++c) {
                int cp=(c/2)*width+c%2;
                float signedSum=0.0, varianceSum=0.0;
                for(int j=0;j<5;++j) {
                    int d= j==0 ? 0 : (j==1 ? -2 : (j==2 ? 2 : (j==3 ? -2*width : 2*width)));
                    int a=p+cp+d,b=q+cp+d;
                    if(!usable(baseline,a,c) || !usable(f,b,c)) return false;
                    float sa=signalAt(baseline,a,c), sb=signalAt(f,b,c);
                    float v=varianceAt(baseline,sa,c)+varianceAt(f,sb,c), delta=sa-sb;
                    if(delta*delta>=16.0*v) return false;
                    signedSum+=delta; varianceSum+=v;
                }
                if(signedSum*signedSum>9.0*varianceSum) return false;
                for(int radius=2;radius<=4;radius+=2) for(int axis=0;axis<2;++axis) {
                    int d=radius*(axis==0?1:width);
                    if(!usable(baseline,p+cp-d,c)||!usable(baseline,p+cp+d,c)||
                        !usable(f,q+cp-d,c)||!usable(f,q+cp+d,c)) return false;
                    float a0=signalAt(baseline,p+cp-d,c),a1=signalAt(baseline,p+cp+d,c);
                    float b0=signalAt(f,q+cp-d,c),b1=signalAt(f,q+cp+d,c);
                    float va=varianceAt(baseline,a0,c)+varianceAt(baseline,a1,c);
                    float vb=varianceAt(f,b0,c)+varianceAt(f,b1,c);
                    float ga=a1-a0,gb=b1-b0,error=ga-gb;
                    if(ga*ga>16.0*va && gb*gb>16.0*vb && ga*gb<0.0) return false;
                    if(error*error>16.0*(va+vb)) return false;
                }
            }
            return true;
        }
        void main() {
            ivec2 at=ivec2(gl_GlobalInvocationID.xy)*2;
            if(at.x>=width || at.y>=height) return;
            int p=at.y*width+at.x;
            for(int c=0;c<4;++c) {
                int pos=p+(c/2)*width+c%2;
                preliminary[pos]=rawAt(baseline,pos);
            }
            if(acceptedMask==(1<<baseline) || !inside(at)) return;
            int positions[3]; int n=0;
            for(int f=0;f<3;++f) {
                positions[f]=p;
                if(f==baseline || (acceptedMask&(1<<f))==0) continue;
                ivec2 q=at+shifts[f];
                if(!inside(q)) return;
                positions[f]=q.y*width+q.x;
                if(!matches(f,p,positions[f])) return;
                ++n;
            }
            if(n==0) return;
            int parity=((at.x/2+at.y/2)&1);
            int tile=((at.y/64)*tileColumns+at.x/64)*4+parity*2;
            float rho=tileBounds[tile],modelRatio=tileBounds[tile+1];
            if(isnan(rho)||isinf(rho)||rho<0.0||rho>1.0||
                isnan(modelRatio)||isinf(modelRatio)||modelRatio<0.0) return;
            float a[3];
            for(int f=0;f<3;++f) a[f]=(acceptedMask&(1<<f))==0 ? 0.0 :
                (f==baseline ? (n==2?0.5:0.625) : (n==2?0.25:0.375));
            float sharedEv=0.35; uint result[4];
            for(int c=0;c<4;++c) {
                float sig[3],v[3],z=0.0,variance=0.0;
                int offset=(c/2)*width+c%2;
                for(int f=0;f<3;++f) {
                    sig[f]=0.0;v[f]=0.0;
                    if(a[f]==0.0) continue;
                    if(!usable(f,positions[f]+offset,c)) return;
                    sig[f]=signalAt(f,positions[f]+offset,c);
                    v[f]=varianceAt(f,sig[f],c);
                    z+=a[f]*sig[f]; variance+=a[f]*a[f]*v[f];
                }
                for(int i=0;i<3;++i) for(int j=i+1;j<3;++j)
                    variance+=2.0*rho*a[i]*a[j]*sqrt(v[i]*v[j]);
                float rms=sqrt(variance)+sqrt(modelRatio*v[baseline]);
                float mse=rms*rms;
                if(!(mse>0.0) || !(mse<v[baseline]) || sig[baseline]>=0.20 ||
                    sig[baseline]-2.0*sqrt(v[baseline])<4.0*rms) return;
                float ev=min(0.35,0.5*log2(v[baseline]/mse))*
                    clamp((0.20-sig[baseline])/0.10,0.0,1.0);
                sharedEv=min(sharedEv,ev);
                int o=baseline*14;
                float value=z*metadata[o+5]*(metadata[o+4]-metadata[o+c])+metadata[o+c];
                result[c]=uint(clamp(floor(value+0.5),0.0,65535.0));
            }
            uint evq=uint(clamp(floor(sharedEv/0.35*65535.0),0.0,65535.0));
            if(evq==0u) return;
            for(int c=0;c<4;++c)
                preliminary[p+(c/2)*width+c%2]=result[c]|(evq<<16);
        }
    """.trimIndent()

    private val FINALIZE_SHADER = """
        #version 310 es
        precision highp int;
        layout(local_size_x=16,local_size_y=8) in;
        layout(std430,binding=0) readonly buffer Sources { uint rawWords[]; };
        layout(std430,binding=1) readonly buffer Temporary { uint preliminary[]; };
        layout(std430,binding=3) writeonly buffer Counts { uvec2 counts[]; };
        layout(std430,binding=4) writeonly buffer Final { uint finalized[]; };
        uniform int width,height,baseline;
        shared uvec2 votes[128];
        uint rawAt(int p) {
            return (rawWords[baseline*(width*height/2)+p/2] >> uint((p&1)*16))&65535u;
        }
        void main() {
            ivec2 cell=ivec2(gl_GlobalInvocationID.xy),at=cell*2;
            uint local=gl_LocalInvocationIndex;
            uvec2 count=uvec2(0);
            if(at.x<width && at.y<height) {
                uint ev=65535u;
                for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx) {
                    ivec2 q=(cell+ivec2(dx,dy))*2;
                    if(q.x<0 || q.y<0 || q.x>=width || q.y>=height) ev=0u;
                    else ev=min(ev,preliminary[q.y*width+q.x]>>16);
                }
                for(int c=0;c<4;++c) {
                    int pos=(at.y+c/2)*width+at.x+c%2;
                    uint before=rawAt(pos);
                    uint after=ev==0u?before:(preliminary[pos]&65535u);
                    finalized[pos]=after|(ev<<16);
                    if(before!=after) count.x++;
                }
                if(ev>0u) count.y=1u;
            }
            votes[local]=count;barrier();
            for(uint n=64u;n>0u;n>>=1u) {
                if(local<n) votes[local]+=votes[local+n];
                barrier();
            }
            if(local==0u) counts[gl_WorkGroupID.y*gl_NumWorkGroups.x+gl_WorkGroupID.x]=votes[0];
        }
    """.trimIndent()

    private val RENDER_SHADER = """
        #version 310 es
        precision highp int;
        precision highp float;
        layout(local_size_x=16,local_size_y=8) in;
        layout(std430,binding=0) writeonly buffer Output { uint pixels[]; };
        layout(std430,binding=4) readonly buffer Final { uint finalized[]; };
        layout(std430,binding=5) readonly buffer Shading { float shadingGains[]; };
        uniform int width,height,cfa;
        uniform int shadingColumns,shadingRows;
        uniform ivec4 shadingArea;
        uniform float color[18];
        const ivec4 channels[4]=ivec4[4](ivec4(0,1,2,3),ivec4(1,0,3,2),
            ivec4(1,3,0,2),ivec4(3,1,2,0));
        int reflected(int x,int size) { return x<0?-x:(x>=size?2*size-2-x:x); }
        int channel(ivec2 at) { return channels[cfa][(at.y&1)*2+(at.x&1)]; }
        float shadingGain(ivec2 at,int ch) {
            if(shadingColumns==0) return 1.0;
            vec2 grid=clamp(vec2(at-shadingArea.xy)/
                vec2(max(ivec2(1),shadingArea.zw-shadingArea.xy-1)),0.0,1.0)*
                vec2(shadingColumns-1,shadingRows-1);
            ivec2 p=ivec2(grid),q=min(p+1,ivec2(shadingColumns-1,shadingRows-1));
            vec2 f=grid-vec2(p);
            float a=shadingGains[(p.y*shadingColumns+p.x)*4+ch];
            float b=shadingGains[(p.y*shadingColumns+q.x)*4+ch];
            float c=shadingGains[(q.y*shadingColumns+p.x)*4+ch];
            float d=shadingGains[(q.y*shadingColumns+q.x)*4+ch];
            float top=a+(b-a)*f.x,bottom=c+(d-c)*f.x;
            return top+(bottom-top)*f.y;
        }
        vec3 clippedCell(ivec2 at) {
            ivec2 cell=at&~ivec2(1);
            int p=cell.y*width+cell.x;
            float closest=1.0,farthest=0.0;
            for(int c=0;c<4;++c) {
                float raw=float(finalized[p+(c/2)*width+c%2]&65535u);
                float margin=max(4.0,(color[4]-color[c])*0.005);
                float h=clamp((color[4]-raw)/margin,0.0,1.0);
                closest=min(closest,h);farthest=max(farthest,h);
            }
            if(closest>=1.0) return vec3(0.0);
            float ceiling=256.0;
            for(int c=0;c<4;++c) {
                ivec2 q=cell+ivec2(c%2,c/2);
                int ch=channel(q);
                ceiling=min(ceiling,shadingGain(q,ch)*color[5+ch]);
            }
            return vec3(1.0-closest*closest*(3.0-2.0*closest),
                1.0-farthest*farthest*(3.0-2.0*farthest),ceiling);
        }
        float sampleAt(ivec2 at) {
            at=ivec2(reflected(at.x,width),reflected(at.y,height));
            int pos=(at.y&1)*2+(at.x&1);
            float raw=float(finalized[at.y*width+at.x]&65535u);
            int ch=channel(at);
            return (max(0.0,(raw-color[pos])/(color[4]-color[pos]))*shadingGain(at,ch))*color[5+ch];
        }
        float pair(float a,float b,float c,float d) {
            float ab=abs(a-b),cd=abs(c-d);
            // Same continuous near-tie transition as CPU; no abrupt average/direction switch.
            float scale=max(1.0,max(max(abs(a),abs(b)),max(abs(c),abs(d))));
            float tie=0.00000095367431640625*scale;
            float transition=0.000244140625*scale;
            float average=((a+b)+(c+d))*0.25,difference=cd-ab;
            float weight=clamp((abs(difference)-tie)/(transition-tie),0.0,1.0);
            float directed=difference>0.0?(a+b)*0.5:(c+d)*0.5;
            return average+(directed-average)*weight;
        }
        void main() {
            ivec2 at=ivec2(gl_GlobalInvocationID.xy);
            if(at.x>=width || at.y>=height) return;
            int ch=channel(at);
            float p=sampleAt(at),l=sampleAt(at+ivec2(-1,0)),r=sampleAt(at+ivec2(1,0));
            float u=sampleAt(at+ivec2(0,-1)),d=sampleAt(at+ivec2(0,1));
            vec3 rgb;
            if(ch==0 || ch==3) {
                rgb[ch==0?0:2]=p;
                rgb.g=pair(l,r,u,d);
                rgb[ch==0?2:0]=pair(sampleAt(at+ivec2(-1,-1)),sampleAt(at+ivec2(1,1)),
                    sampleAt(at+ivec2(1,-1)),sampleAt(at+ivec2(-1,1)));
            } else {
                rgb.g=p;
                bool horizontalRed=channel(at+ivec2(-1,0))==0;
                rgb[horizontalRed?0:2]=(l+r)*0.5;
                rgb[horizontalRed?2:0]=(u+d)*0.5;
            }
            vec3 clipping=clippedCell(at); // any confidence, all confidence, corrected ceiling
            if(clipping.x>0.0) rgb+=(min(rgb,vec3(clipping.z))-rgb)*clipping.x;
            vec3 linear;
            for(int c=0;c<3;++c) {
                int i=9+c*3;
                linear[c]=(color[i]*rgb.r+color[i+1]*rgb.g)+color[i+2]*rgb.b;
            }
            float minimum=min(linear.r,min(linear.g,linear.b));
            if(minimum<0.0) {
                float luma=(0.2126*linear.r+0.7152*linear.g)+0.0722*linear.b;
                float chroma=luma>0.0?luma/(luma-minimum):0.0;
                linear=luma>0.0?max(vec3(0.0),vec3(luma)+(linear-vec3(luma))*chroma):vec3(0.0);
            }
            float gamutPeak=max(linear.r,max(linear.g,linear.b));
            if(gamutPeak>1.0) linear/=gamutPeak;
            float y=(0.2126*linear.r+0.7152*linear.g)+0.0722*linear.b,gain=1.0;
            if(y>0.0 && y<0.25) {
                uint evq=finalized[(at.y&~1)*width+(at.x&~1)]>>16;
                float ev=float(evq)*(0.35/65535.0),t=1.0-y/0.25;
                gain=1.0+(exp2(ev)-1.0)*t*t;
                float peak=max(linear.r,max(linear.g,linear.b));
                if(peak>0.0) gain=min(gain,max(1.0,1.0/peak));
            }
            vec3 value=clamp(linear*gain,0.0,1.0);
            value+=(vec3(1.0)-value)*clipping.y;
            for(int c=0;c<3;++c) value[c]=value[c]<=0.0031308?12.92*value[c]:
                1.055*pow(value[c],1.0/2.4)-0.055;
            uvec3 q=uvec3(clamp(floor(value*255.0+0.5),0.0,255.0));
            pixels[at.y*width+at.x]=q.r|(q.g<<8)|(q.b<<16)|0xff000000u;
        }
    """.trimIndent()
}
