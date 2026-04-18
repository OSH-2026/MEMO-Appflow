#include "predictor/native_score_utils.h"
#include "util/native_logger.h"
#include <numeric>

namespace memoos {
std::vector<float> normalize_scores(const std::vector<float>& scores) {
    const float total = std::accumulate(scores.begin(), scores.end(), 0.0f);
    if (total <= 0.0f) {
        return scores;
    }
    std::vector<float> normalized;
    normalized.reserve(scores.size());
    for (float score : scores) {
        normalized.push_back(score / total);
    }
    return normalized;
}
void normalize(int length,float* values){
    memoos::log_info("Normalizing score vector in native bridge");
    if(length==0){
        return;
    }
    float sum=0;
    for(int i=0;i<length;++i){
        sum+=values[i];
    }
    for(int i=0;i<length;++i){
        values[i]/=sum;
    }
}
}
