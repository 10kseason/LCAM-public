#include <jni.h>

#include "lunacore/raw_hdr.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeRawHdr_nativeFuse(
    JNIEnv* env, jobject, jint width, jint height, jobjectArray buffers,
    jdoubleArray metadata, jobject destination, jlongArray statistics, jintArray active_area) {
    if (!buffers || !metadata || !destination || !statistics || !active_area ||
        width < 64 || height < 64 || env->GetArrayLength(buffers) != 3 ||
        env->GetArrayLength(metadata) != 42 || env->GetArrayLength(statistics) != 10 ||
        env->GetArrayLength(active_area) != 4) return JNI_FALSE;
    const auto pixels = static_cast<jlong>(width) * height;
    if (pixels > 16000000 || env->GetDirectBufferCapacity(destination) != pixels * 4)
        return JNI_FALSE;
    auto* output = static_cast<std::uint32_t*>(env->GetDirectBufferAddress(destination));
    if (!output) return JNI_FALSE;
    std::array<jdouble, 42> values{};
    env->GetDoubleArrayRegion(metadata, 0, 42, values.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    std::array<lunacore::RawFrame, 3> frames{};
    for (int f = 0; f < 3; ++f) {
        jobject buffer = env->GetObjectArrayElement(buffers, f);
        if (!buffer) return JNI_FALSE;
        const auto capacity = env->GetDirectBufferCapacity(buffer);
        frames[f].pixels = static_cast<const std::uint16_t*>(env->GetDirectBufferAddress(buffer));
        env->DeleteLocalRef(buffer);
        if (capacity != pixels * 2 || !frames[f].pixels) return JNI_FALSE;
        auto& m = frames[f].metadata;
        for (int c = 0; c < 4; ++c) {
            m.black[c] = values[f * 14 + c];
            m.slope[c] = values[f * 14 + 6 + c];
            m.intercept[c] = values[f * 14 + 10 + c];
        }
        m.white = values[f * 14 + 4];
        m.exposure = values[f * 14 + 5];
    }
    std::array<jint, 4> area{};
    env->GetIntArrayRegion(active_area, 0, 4, area.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    lunacore::RawHdrStats stats;
    if (!lunacore::fuse_raw_hdr(frames, width, height, output, stats,
                               {area[0], area[1], area[2], area[3]})) return JNI_FALSE;
    std::array<jlong, 10> result{
        stats.reference, stats.accepted_mask, static_cast<jlong>(stats.merged_pixels),
        static_cast<jlong>(stats.rejected_cells)};
    for (int i = 0; i < 6; ++i) result[4 + i] = stats.shifts[i];
    env->SetLongArrayRegion(statistics, 0, 10, result.data());
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}
