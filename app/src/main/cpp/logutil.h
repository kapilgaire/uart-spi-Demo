//
// Created by tom on 2025/12/5.
//

#ifndef AIMARKET2_LOGUTIL_H
#define AIMARKET2_LOGUTIL_H

#include <android/log.h>

#define TAG "ql-log"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG,__VA_ARGS__)
// 定义debug信息
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
// 定义error信息
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)

#endif //AIMARKET2_LOGUTIL_H
