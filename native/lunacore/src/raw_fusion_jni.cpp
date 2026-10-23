#include <jni.h>

#include "lunacore/raw_fusion.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeRawFusion_nativeFuse(
    JNIEnv* env, jobject, jint width, jint height, jobjectArray buffers,
    jdoubleArray metadata, jobject destination, jdoubleArray statistics, jintArray active_area) {
    if (!buffers || !metadata || !destination || !statistics || !active_area ||
        env->GetArrayLength(active_area) != 4 || width < 64 || height < 64 ||
        env->GetArrayLength(buffers) != 3 || env->GetArrayLength(metadata) != 42 ||
        env->GetArrayLength(statistics) != 12) return JNI_FALSE;
    const auto bytes = static_cast<jlong>(width) * height * 2;
    if (bytes <= 0 || bytes > 32000000 || env->GetDirectBufferCapacity(destination) != bytes)
        return JNI_FALSE;
    auto* output = static_cast<std::uint16_t*>(env->GetDirectBufferAddress(destination));
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
        if (capacity != bytes || !frames[f].pixels) return JNI_FALSE;
        auto& m = frames[f].metadata;
        for (int c = 0; c < 4; ++c) {
            m.black[c] = values[f * 14 + c];
            m.slope[c] = values[f * 14 + 6 + c];
            m.intercept[c] = values[f * 14 + 10 + c];
        }
        m.white = values[f * 14 + 4];
        m.exposure = values[f * 14 + 5];
    }
    lunacore::RawFusionStats stats;
    std::array<jint, 4> area{};
    env->GetIntArrayRegion(active_area, 0, 4, area.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    const std::array<int, 4> roi{area[0], area[1], area[2], area[3]};
    if (!lunacore::fuse_raw(frames, width, height, output, stats, roi)) return JNI_FALSE;
    std::array<jdouble, 12> result{
        static_cast<double>(stats.baseline), static_cast<double>(stats.accepted_frames),
        static_cast<double>(stats.changed), static_cast<double>(stats.merged),
        static_cast<double>(stats.rejected), stats.mean_effective_samples,
    };
    for (int i = 0; i < 6; ++i) result[6 + i] = stats.shifts[i];
    env->SetDoubleArrayRegion(statistics, 0, 12, result.data());
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}
