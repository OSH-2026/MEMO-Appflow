#include "bridge/memo_native_bridge.h"
#include "policy/native_policy_utils.h"
#include "predictor/native_score_utils.h"
#include "util/native_logger.h"
#include <vector>
#include <string>
JNIEXPORT void JNICALL destructor(jlong pointer){
    delete reinterpret_cast<transformer::TransformerPredictor*>(pointer);
}
JNIEXPORT jlong JNICALL constructor(jsize l1,jbyte* str1,jsize l2,jbyte* str2){
    std::string s1,s2;
    s1.assign(reinterpret_cast<char*>(str1),l1);
    s2.assign(reinterpret_cast<char*>(str2),l2);
    return reinterpret_cast<jlong>(new transformer::TransformerPredictor(s1,s2));
}
JNIEXPORT void JNICALL clear(jlong ptr){
    reinterpret_cast<transformer::TransformerPredictor*>(ptr)->clear();
}
JNIEXPORT jlong JNICALL getEventCount(jlong ptr){
    return reinterpret_cast<transformer::TransformerPredictor*>(ptr)->getEventCount();
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6);
    jclass clz=env->FindClass("com/memoos/system/bridge/NativeScoreBridge");
    JNINativeMethod methods[2];
    methods[0]={"normalize","([F)V",(void*)memoos::normalize};
    methods[1]={"mergeThresholds","([F)F",(void*)memoos::merge_thresholds0};
    env->RegisterNatives(clz,methods,2);
    jclass pred=env->FindClass("com/memoos/predictor/engine/TransformerPredictor");
    JNINativeMethod methodss[7];
    methodss[0]={"destructor","(J)V",reinterpret_cast<void*>(destructor)};
    methodss[1]={"constructor","()J",reinterpret_cast<void*>(constructor)};
    methodss[2]={"clear","(J)V",reinterpret_cast<void*>(clear)};
    return JNI_VERSION_1_6;
}