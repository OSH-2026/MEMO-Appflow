#ifndef MEMO_NATIVE_BRIDGE_H
#define MEMO_NATIVE_BRIDGE_H

#include <jni.h>

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_memoos_system_bridge_NativeScoreBridge_normalizeScoresNative(
        JNIEnv* env,
        jobject thiz,
        jfloatArray scores);

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_memoos_system_bridge_NativeScoreBridge_mergeThresholdsNative(
        JNIEnv* env,
        jobject thiz,
        jfloatArray thresholds);

#endif
