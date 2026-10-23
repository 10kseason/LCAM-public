#include <android/bitmap.h>
#include <jni.h>
#include "lunacore/detail_guard.h"

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    const auto exception = env->FindClass(type);
    if (exception) env->ThrowNew(exception, message);
}
struct Lock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    bool locked = false;
    bool acquire() {
        locked = AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
        return locked;
    }
    ~Lock() { if (locked) AndroidBitmap_unlockPixels(env, bitmap); }
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_dev_lunacam_processing_nativebridge_NativeDetailGuard_nativeProcess(
    JNIEnv* env, jobject /* instance */, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env, "java/lang/IllegalArgumentException", "Detail Guard requires RGBA_8888 bitmap");
        return 0;
    }
    Lock lock{env, bitmap};
    if (!lock.acquire()) {
        fail(env, "java/lang/IllegalStateException", "Unable to lock Detail Guard bitmap");
        return 0;
    }
    lunacore::DetailGuardStats stats;
    const auto error = lunacore::detail_guard_rgba(static_cast<std::uint8_t*>(lock.pixels),
        info.width, info.height, info.stride, stats);
    if (error == lunacore::DetailGuardError::allocation_failed)
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate Detail Guard source rows");
    else if (error != lunacore::DetailGuardError::none)
        fail(env, "java/lang/IllegalArgumentException", "Invalid Detail Guard dimensions");
    return static_cast<jlong>(stats.denoised + stats.sharpened);
}
