#include <android/bitmap.h>
#include <jni.h>
#include "lunacore/hdr_fusion.h"

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    const auto exception = env->FindClass(type);
    if (exception) env->ThrowNew(exception, message);
}
struct Lock {
    JNIEnv* env;
    jobject bitmap;
    void* data = nullptr;
    bool locked = false;
    bool acquire() {
        locked = AndroidBitmap_lockPixels(env, bitmap, &data) == ANDROID_BITMAP_RESULT_SUCCESS;
        return locked;
    }
    ~Lock() { if (locked) AndroidBitmap_unlockPixels(env, bitmap); }
};
}
extern "C" JNIEXPORT void JNICALL
Java_dev_lunacam_processing_nativebridge_NativeHdrFusion_nativeMerge(
    JNIEnv* env, jobject /* instance */, jobject base, jobject shorter, jdouble ratio,
    jlongArray results, jdoubleArray diagnostics) {
    AndroidBitmapInfo a{}, b{};
    if (!base || !shorter || env->IsSameObject(base, shorter) || !results ||
        env->GetArrayLength(results) != 5 || !diagnostics || env->GetArrayLength(diagnostics) != 9 ||
        AndroidBitmap_getInfo(env, base, &a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, shorter, &b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || b.format != a.format ||
        a.width != b.width || a.height != b.height) {
        fail(env, "java/lang/IllegalArgumentException", "HDR requires two distinct equal RGBA bitmaps");
        return;
    }
    Lock base_lock{env, base}, short_lock{env, shorter};
    if (!base_lock.acquire() || !short_lock.acquire()) {
        fail(env, "java/lang/IllegalStateException", "Unable to lock HDR bitmaps");
        return;
    }
    lunacore::HdrStats stats;
    const auto error = lunacore::hdr_fuse_rgba(
        static_cast<std::uint8_t*>(base_lock.data), a.stride,
        static_cast<const std::uint8_t*>(short_lock.data), b.stride,
        static_cast<int>(a.width), static_cast<int>(a.height), ratio, stats);
    if (error == lunacore::HdrError::invalid_image) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid HDR image dimensions or exposure ratio");
        return;
    }
    if (error == lunacore::HdrError::allocation_failed) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate HDR confidence grid");
        return;
    }
    const jlong values[] = {static_cast<jlong>(stats.changed), stats.accepted ? 1 : 0,
        stats.shift_x, stats.shift_y, static_cast<jlong>(stats.reason)};
    env->SetLongArrayRegion(results, 0, 5, values);
    const jdouble metrics[] = {static_cast<double>(stats.alignment_anchors), stats.alignment_cost,
        stats.alignment_margin, static_cast<double>(stats.calibration_samples),
        static_cast<double>(stats.calibration_knots), stats.calibration_inlier_fraction,
        stats.calibration_rmse, stats.calibration_min_luma, stats.calibration_max_luma};
    env->SetDoubleArrayRegion(diagnostics, 0, 9, metrics);
}
