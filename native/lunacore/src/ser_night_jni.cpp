#include <jni.h>
#include <new>
#include "lunacore/ser_night.h"

namespace {
bool read(JNIEnv* env,int w,int h,jobjectArray buffers,jdoubleArray metadata,jintArray area,
          std::array<lunacore::RawFrame,3>& frames,std::array<int,4>& roi) {
    if(!buffers||!metadata||!area||w<64||h<64||w%2||h%2||
        static_cast<jlong>(w)*h>16000000||env->GetArrayLength(buffers)!=3||
        env->GetArrayLength(metadata)!=42||env->GetArrayLength(area)!=4)return false;
    std::array<jdouble,42> values{};std::array<jint,4> raw_area{};
    env->GetDoubleArrayRegion(metadata,0,42,values.data());env->GetIntArrayRegion(area,0,4,raw_area.data());
    if(env->ExceptionCheck())return false;
    for(int i=0;i<4;++i)roi[i]=raw_area[i];
    for(int f=0;f<3;++f) {
        jobject buffer=env->GetObjectArrayElement(buffers,f);
        if(!buffer)return false;
        const auto capacity=env->GetDirectBufferCapacity(buffer);
        frames[f].pixels=static_cast<const std::uint16_t*>(env->GetDirectBufferAddress(buffer));
        env->DeleteLocalRef(buffer);
        if(!frames[f].pixels||capacity!=static_cast<jlong>(w)*h*2)return false;
        auto& m=frames[f].metadata;
        for(int c=0;c<4;++c){m.black[c]=values[f*14+c];m.slope[c]=values[f*14+6+c];
            m.intercept[c]=values[f*14+10+c];}
        m.white=values[f*14+4];m.exposure=values[f*14+5];
    }
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeSerNight_nativePrepare(
    JNIEnv* env,jobject,jint w,jint h,jobjectArray buffers,jdoubleArray metadata,jintArray area,
    jintArray statistics,jfloatArray bounds) {
    std::array<lunacore::RawFrame,3> frames{};std::array<int,4> roi{};
    if(!read(env,w,h,buffers,metadata,area,frames,roi)||!statistics||!bounds||
        env->GetArrayLength(statistics)!=8||env->GetArrayLength(bounds)!=((w+63)/64)*((h+63)/64)*4)
        return JNI_FALSE;
    lunacore::SerNightPlan plan;
    if(!lunacore::prepare_ser_night(frames,w,h,roi,plan))return JNI_FALSE;
    std::array<jint,8> stats{plan.baseline,plan.accepted_mask};
    for(int i=0;i<6;++i)stats[i+2]=plan.shifts[i];
    env->SetIntArrayRegion(statistics,0,8,stats.data());
    env->SetFloatArrayRegion(bounds,0,static_cast<jsize>(plan.tile_bounds.size()),plan.tile_bounds.data());
    return env->ExceptionCheck()?JNI_FALSE:JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_lunacam_processing_nativebridge_NativeSerNight_nativeFuse(
    JNIEnv* env,jobject,jint w,jint h,jobjectArray buffers,jdoubleArray metadata,jintArray area,
    jintArray plan_array,jfloatArray bounds,jobject destination,jlongArray statistics,jboolean enabled) {
    std::array<lunacore::RawFrame,3> frames{};std::array<int,4> roi{};
    if(!read(env,w,h,buffers,metadata,area,frames,roi)||!plan_array||!bounds||!destination||!statistics||
        env->GetArrayLength(plan_array)!=8||env->GetArrayLength(statistics)!=2||
        env->GetDirectBufferCapacity(destination)!=static_cast<jlong>(w)*h*4)return JNI_FALSE;
    auto* output=static_cast<std::uint32_t*>(env->GetDirectBufferAddress(destination));
    if(!output)return JNI_FALSE;
    const int count=((w+63)/64)*((h+63)/64)*4;
    if(env->GetArrayLength(bounds)!=count)return JNI_FALSE;
    try {
        lunacore::SerNightPlan plan;std::array<jint,8> values{};
        env->GetIntArrayRegion(plan_array,0,8,values.data());
        if(env->ExceptionCheck())return JNI_FALSE;
        plan.baseline=values[0];plan.accepted_mask=values[1];plan.active_area=roi;
        plan.tile_columns=(w+63)/64;plan.tile_rows=(h+63)/64;
        for(int i=0;i<6;++i)plan.shifts[i]=values[i+2];
        plan.tile_bounds.resize(static_cast<std::size_t>(count));
        env->GetFloatArrayRegion(bounds,0,count,plan.tile_bounds.data());
        if(env->ExceptionCheck())return JNI_FALSE;
        lunacore::SerNightStats stats;
        if(!lunacore::fuse_ser_night(frames,w,h,plan,output,stats,enabled==JNI_TRUE))return JNI_FALSE;
        const std::array<jlong,2> result{static_cast<jlong>(stats.changed),static_cast<jlong>(stats.lifted_cells)};
        env->SetLongArrayRegion(statistics,0,2,result.data());
        return env->ExceptionCheck()?JNI_FALSE:JNI_TRUE;
    }catch(const std::bad_alloc&){return JNI_FALSE;}
}
