#include "bridge/memo_native_bridge.h"
#include "policy/native_policy_utils.h"
#include "predictor/native_score_utils.h"
#include "util/native_logger.h"
#include <vector>

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_memoos_system_bridge_NativeScoreBridge_normalizeScoresNative(
        JNIEnv* env,
        jobject thiz,
        jfloatArray scores) {
    const jsize length = env->GetArrayLength(scores);
    std::vector<float> raw(length);
    env->GetFloatArrayRegion(scores, 0, length, raw.data());
    memoos::log_info("Normalizing score vector in native bridge");
    const std::vector<float> normalized = memoos::normalize_scores(raw);
    jfloatArray result = env->NewFloatArray(length);
    env->SetFloatArrayRegion(result, 0, length, normalized.data());
    return result;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_memoos_system_bridge_NativeScoreBridge_mergeThresholdsNative(
        JNIEnv* env,
        jobject thiz,
        jfloatArray thresholds) {
    const jsize length = env->GetArrayLength(thresholds);
    std::vector<float> raw(length);
    env->GetFloatArrayRegion(thresholds, 0, length, raw.data());
    memoos::log_info("Merging policy thresholds in native bridge");
    return memoos::merge_thresholds(raw);
}
