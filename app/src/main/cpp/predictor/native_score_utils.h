#ifndef MEMO_NATIVE_SCORE_UTILS_H
#define MEMO_NATIVE_SCORE_UTILS_H

#include <vector>

namespace memoos {
std::vector<float> normalize_scores(const std::vector<float>& scores);
void normalize(int length,float* values);
}
#endif
