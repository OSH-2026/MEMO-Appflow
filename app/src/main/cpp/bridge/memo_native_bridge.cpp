#include "bridge/memo_native_bridge.h"
#include "policy/native_policy_utils.h"
#include "predictor/native_score_utils.h"
#include "util/native_logger.h"
#include <vector>
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6);
    jclass clz=env->FindClass("com/memoos/system/bridge/NativeScoreBridge");
    JNINativeMethod methods[2];
    methods[0]={"normalize","([F)V",(void*)memoos::normalize};
    methods[1]={"mergeThresholds","([F)F",(void*)memoos::merge_thresholds0};
    env->RegisterNatives(clz,methods,2);
    return JNI_VERSION_1_6;
}