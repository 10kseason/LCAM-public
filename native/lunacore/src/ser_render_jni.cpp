#include "lunacore/ser_render.h"
#include <android/bitmap.h>
#include <jni.h>

namespace {
jboolean render(JNIEnv* env,jint width,jint height,jobject packed,jint cfa,
    jfloatArray metadata,jobject bitmap,jfloatArray shadingGains,jint shadingColumns,
    jint shadingRows,jintArray shadingArea,const lunacore::SerNaturalTone* tone) {
    if(!packed || !metadata || !bitmap || width<4 || height<4 ||
       static_cast<jlong>(width)*height>16000000 || env->GetArrayLength(metadata)!=18) return JNI_FALSE;
    const auto capacity=env->GetDirectBufferCapacity(packed);
    const auto* source=static_cast<const std::uint32_t*>(env->GetDirectBufferAddress(packed));
    if(!source || capacity!=static_cast<jlong>(width)*height*4) return JNI_FALSE;
    float m[18]; env->GetFloatArrayRegion(metadata,0,18,m);
    if(env->ExceptionCheck()) return JNI_FALSE;
    // Bounded 64 KiB stack storage; no heap allocation while the bitmap is locked.
    float gains[64*64*4];
    lunacore::SerRenderShading shading{};
    if(shadingGains) {
        if(shadingColumns<1 || shadingColumns>64 || shadingRows<1 || shadingRows>64 ||
           !shadingArea || env->GetArrayLength(shadingArea)!=4 ||
           env->GetArrayLength(shadingGains)!=shadingColumns*shadingRows*4) return JNI_FALSE;
        jint area[4];env->GetIntArrayRegion(shadingArea,0,4,area);
        env->GetFloatArrayRegion(shadingGains,0,shadingColumns*shadingRows*4,gains);
        if(env->ExceptionCheck()) return JNI_FALSE;
        shading={gains,shadingColumns,shadingRows,area[0],area[1],area[2],area[3]};
    } else if(shadingColumns!=0 || shadingRows!=0) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width!=static_cast<unsigned>(width) ||
       info.height!=static_cast<unsigned>(height)) return JNI_FALSE;
    void* destination=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&destination)!=ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    const auto* correction=shadingGains ? &shading : nullptr;
    auto* rgba=static_cast<std::uint8_t*>(destination);
    const bool ok=tone ? lunacore::ser_render_natural(source,width,height,cfa,m,rgba,
        info.stride,*tone,correction) : lunacore::ser_render(source,width,height,cfa,m,
        rgba,info.stride,correction);
    AndroidBitmap_unlockPixels(env,bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeSerRender_nativeRender(
    JNIEnv* env,jobject,jint width,jint height,jobject packed,jint cfa,
    jfloatArray metadata,jobject bitmap,jfloatArray shadingGains,jint shadingColumns,
    jint shadingRows,jintArray shadingArea) {
    return render(env,width,height,packed,cfa,metadata,bitmap,shadingGains,shadingColumns,
                  shadingRows,shadingArea,nullptr);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeSerRender_nativeRenderNatural(
    JNIEnv* env,jobject,jint width,jint height,jobject packed,jint cfa,
    jfloatArray metadata,jobject bitmap,jfloatArray shadingGains,jint shadingColumns,
    jint shadingRows,jintArray shadingArea,jfloat exposureGain,jfloat shadowStrength,
    jfloat noiseFloor) {
    const lunacore::SerNaturalTone tone{exposureGain,shadowStrength,noiseFloor};
    return render(env,width,height,packed,cfa,metadata,bitmap,shadingGains,shadingColumns,
                  shadingRows,shadingArea,&tone);
}
