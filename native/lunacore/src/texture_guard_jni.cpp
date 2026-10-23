#include <android/bitmap.h>
#include <jni.h>

#include "lunacore/texture_guard.h"

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}
class LockedBitmap {
public:
    LockedBitmap(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {}
    ~LockedBitmap() { if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_); }
    bool lock() {
        locked_ = AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) == ANDROID_BITMAP_RESULT_SUCCESS;
        return locked_;
    }
    std::uint8_t* pixels() const { return static_cast<std::uint8_t*>(pixels_); }
private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_ = nullptr;
    bool locked_ = false;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_lunacam_processing_nativebridge_NativeTextureGuard_nativeProcess(
    JNIEnv* env, jobject /* instance */, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (bitmap == nullptr || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env, "java/lang/IllegalArgumentException", "Texture Guard requires RGBA_8888 bitmap");
        return 0;
    }
    LockedBitmap locked(env, bitmap);
    if (!locked.lock()) {
        fail(env, "java/lang/IllegalStateException", "Unable to lock Texture Guard bitmap");
        return 0;
    }
    std::uint64_t changed = 0;
    const auto result = lunacore::texture_guard_rgba(
        locked.pixels(), info.width, info.height, info.stride, changed);
    if (result == lunacore::TextureGuardError::allocation_failed) {
        fail(env, "java/lang/OutOfMemoryError", "Unable to allocate Texture Guard row cache");
    } else if (result != lunacore::TextureGuardError::none) {
        fail(env, "java/lang/IllegalArgumentException", "Unsupported Texture Guard image dimensions");
    }
    return static_cast<jlong>(changed);
}
