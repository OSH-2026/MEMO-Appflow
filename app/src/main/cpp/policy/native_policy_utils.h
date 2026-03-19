#ifndef MEMO_NATIVE_POLICY_UTILS_H
#define MEMO_NATIVE_POLICY_UTILS_H

#include <vector>

namespace memoos {
float merge_thresholds(const std::vector<float>& thresholds);
float merge_thresholds0(int length,const float* array);
}

#endif
