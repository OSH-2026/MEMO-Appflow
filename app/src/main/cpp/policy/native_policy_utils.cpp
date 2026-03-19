#include "policy/native_policy_utils.h"
#include <numeric>

namespace memoos {
float merge_thresholds(const std::vector<float>& thresholds) {
    if (thresholds.empty()) {
        return 0.0f;
    }
    const float sum = std::accumulate(thresholds.begin(), thresholds.end(), 0.0f);
    return sum / static_cast<float>(thresholds.size());
}
}
