#include <jni.h>
#include <string>

#include "logutil.h"

extern "C"
JNIEXPORT void JNICALL
Java_com_quectel_demo_EnvSet_initEnv(JNIEnv *env, jclass clazz, jstring native_dir) {
    if (native_dir == nullptr) {
        ALOGE("initEnv: native_dir is null");
        return;
    }

    const char *lib_dir = env->GetStringUTFChars(native_dir, nullptr);
    if (lib_dir == nullptr) {
        return; // JNI allocation failed (OutOfMemory)
    }

    std::string nativeStr(lib_dir);
    env->ReleaseStringUTFChars(native_dir, lib_dir);

    ALOGI("initEnv %s", nativeStr.c_str());
}
