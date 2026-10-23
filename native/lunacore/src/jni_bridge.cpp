#include <jni.h>

#include "lunacore/version.h"

extern "C" JNIEXPORT jstring JNICALL
Java_dev_lunacam_processing_nativebridge_NativeLunaCoreVersionProvider_nativeVersion(
    JNIEnv* environment,
    jobject /* provider */) {
    return environment->NewStringUTF(lunacore::version());
}
