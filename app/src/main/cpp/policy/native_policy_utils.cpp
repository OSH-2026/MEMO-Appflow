#include "policy/native_policy_utils.h"
#include "util/native_logger.h"
#include <numeric>

namespace memoos {
float merge_thresholds(const std::vector<float>& thresholds) {
    if (thresholds.empty()) {
        return 0.0f;
    }
    const float sum = std::accumulate(thresholds.begin(), thresholds.end(), 0.0f);
    return sum / static_cast<float>(thresholds.size());
}
float merge_thresholds0(int length,const float* arr){
    memoos::log_info("Merging policy thresholds in native bridge");
    if(length==0) return 0;
    float sum=0;
    for(int i=0;i<length;++i){
	    sum+=arr[i];
    }
    return sum/length;
}
}
