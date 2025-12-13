#include <jni.h>
#include <string>

#include<iostream>

#ifdef LINUX
#include<sys/time.h>
#endif

#include "logutil.h"
#include <cstdlib>
#include <iostream>
#include <sstream>

void SetAdspLibraryPath(const std::string& nativeLibPath) {
    std::stringstream adspPath;
    std::stringstream ldPath;
//    adspPath << nativeLibPath << ";";
    adspPath << nativeLibPath << ";/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    //spath << nativeLibPath << ";/vendor/lib/rfsa/adsp;/vendor/dsp;/vendor/dsp/cdsp";
    ldPath << nativeLibPath;
    ALOGE("nativeLibPath is %s", nativeLibPath.c_str());
    if (setenv("ADSP_LIBRARY_PATH", adspPath.str().c_str(), 1 /*override*/) == 0) {
        ALOGE("SNPE环境配置成功");
    } else {
        ALOGE("SNPE环境配置失败");
    }
    if (setenv("LD_LIBRARY_PATH", ldPath.str().c_str(), 1 /*override*/) == 0) {
        ALOGE("SNPE环境配置成功");
    } else {
        ALOGE("SNPE环境配置失败");
    }
    ALOGE("ADSP_LIBRARY_PATH is %s", getenv("ADSP_LIBRARY_PATH"));
    ALOGE("LD_LIBRARY_PATH is %s", getenv("LD_LIBRARY_PATH"));
}


extern "C"
JNIEXPORT void JNICALL
Java_com_quectel_demo_EnvSet_initEnv(JNIEnv *env, jclass clazz, jstring native_dir) {
    const char *lib_dir = env->GetStringUTFChars(native_dir, nullptr);
    int ret = 0;

    std::string nativeStr(lib_dir);

    SetAdspLibraryPath(nativeStr);

//    ret = setenv("LD_LIBRARY_PATH", lib_dir, 1);
//    ALOGI("config LD_LIBRARY_PATH ret: %d \n", ret);
//
//    std::string adspPath = std::string(lib_dir) +
////                           ";/vendor/lib/rfsa/adsp" +
////                           ";/vendor/dsp" +
////                           ";/vendor/dsp/cdsp";
//                           ";/system/vendor/lib/rfsa/adsp" +
//                           ";/system/vendor/dsp" +
//                           ";/system/vendor/dsp/cdsp";
//
//    ret = setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1);
//    ALOGI("config ADSP_LIBRARY_PATH ret : %d \n", ret);

}