#include "transformer_predictor.h"
#include <cmath>
#include <algorithm>
#include <iostream>

namespace transformer {

TransformerPredictor::TransformerPredictor(
    const std::string& weightsPath, 
    const std::string& manifestPath)
    : inferencer0_(weightsPath, manifestPath),
      inferencer1_(weightsPath, manifestPath),
      eventCount_(0),
      nextId_(0) {
}

void TransformerPredictor::clear() {
    inferencer0_.clear_cache();
    inferencer1_.clear_cache();
    eventCount_ = 0;
    nameToId_.clear();
    idToName_.clear();
    nextId_ = 0;
}

int TransformerPredictor::getOrCreateAppId(const std::string& appName) {
    auto it = nameToId_.find(appName);
    if (it != nameToId_.end()) {
        return it->second;
    }
    
    // 新 App，分配 ID（循环复用）
    int id = nextId_ % MAX_APP_IDS;
    nextId_++;
    
    // 如果该 ID 已被占用，清除旧映射
    auto oldIt = idToName_.find(id);
    if (oldIt != idToName_.end()) {
        nameToId_.erase(oldIt->second);
        idToName_.erase(oldIt);
    }
    
    nameToId_[appName] = id;
    idToName_[id] = appName;
    return id;
}

std::string TransformerPredictor::getAppNameById(int appId) {
    auto it = idToName_.find(appId);
    if (it != idToName_.end()) {
        return it->second;
    }
    return "<unknown_id_" + std::to_string(appId) + ">";
}

void TransformerPredictor::addToken(
    TransformerInferencer& inf, 
    int appId, 
    int action, 
    float timeMin,
    float hour) {
    
    // 构造单个 token
    std::vector<std::tuple<int, int, float, float>> tokens;
    tokens.emplace_back(appId, action, timeMin, hour);
    
    // 使用 add_cache 追加
    inf.add_cache(tokens);
}

bool TransformerPredictor::shouldUseBothInferencers() const {
    // 当事件数超过半窗口后，同时向两个推理器写入
    return eventCount_ >= HALF_EVENTS;
}

bool TransformerPredictor::shouldClearInferencer0() const {
    // 在 MAX, 2*MAX, 3*MAX... 清空 (1536, 3072, 4608...)
    return eventCount_ > 0 && (eventCount_ % MAX_EVENTS == 0);
}

bool TransformerPredictor::shouldClearInferencer1() const {
    // 在 MAX+HALF, 2*MAX+HALF... 清空 (2304, 3840...)
    return eventCount_ > HALF_EVENTS && ((eventCount_ - HALF_EVENTS) % MAX_EVENTS == 0);
}

void TransformerPredictor::addEvent(const AppEvent& event) {
    // 分配 ID
    int appId = getOrCreateAppId(event.appName);
    
    // 检查是否需要清空某个推理器
    if (shouldClearInferencer0()) {
        inferencer0_.clear_cache();
    }
    if (shouldClearInferencer1()) {
        inferencer1_.clear_cache();
    }
    
    // 添加打开 token (action=1)，使用传入的 startHourOfDay
    addToken(inferencer0_, appId, 1, event.startTimeMinutes, event.startHourOfDay);
    
    // 添加关闭 token (action=0)，使用传入的 endHourOfDay
    addToken(inferencer0_, appId, 0, event.endTimeMinutes, event.endHourOfDay);
    
    // 如果需要同时写入第二个推理器
    if (shouldUseBothInferencers()) {
        addToken(inferencer1_, appId, 1, event.startTimeMinutes, event.startHourOfDay);
        addToken(inferencer1_, appId, 0, event.endTimeMinutes, event.endHourOfDay);
    }
    
    eventCount_++;
}

void TransformerPredictor::addEvents(const std::vector<AppEvent>& events) {
    for (const auto& event : events) {
        addEvent(event);
    }
}

TransformerInferencer& TransformerPredictor::getPrimaryInferencer() {
    if (eventCount_ <= HALF_EVENTS) {
        // 前 768 个事件，使用 inferencer0
        return inferencer0_;
    }
    // 以 HALF 为周期交替: 
    // phase=1 (769-1536): i0, phase=2 (1537-2304): i1, phase=3 (2305-3072): i0...
    size_t phase = (eventCount_ - 1) / HALF_EVENTS;
    return (phase % 2 == 1) ? inferencer0_ : inferencer1_;
}

std::vector<Prediction> TransformerPredictor::predict(int topK) {
    std::vector<Prediction> results;
    
    if (eventCount_ == 0) {
        return results;
    }
    
    // 获取主推理器
    TransformerInferencer& primary = getPrimaryInferencer();
    
    // 调用 decode 获取概率分布（200维）
    std::vector<float> probs = primary.decode();
    
    if (probs.empty()) {
        return results;
    }
    
    // 构建 (prob, id) 列表并排序
    std::vector<std::pair<float, int>> indexedProbs;
    indexedProbs.reserve(probs.size());
    for (size_t i = 0; i < probs.size(); ++i) {
        indexedProbs.emplace_back(probs[i], static_cast<int>(i));
    }
    
    // 按概率降序排序
    std::partial_sort(indexedProbs.begin(), 
                      indexedProbs.begin() + std::min(topK, (int)indexedProbs.size()),
                      indexedProbs.end(),
                      [](const auto& a, const auto& b) { return a.first > b.first; });
    
    // 转换为结果
    int count = std::min(topK, (int)indexedProbs.size());
    for (int i = 0; i < count; ++i) {
        int appId = indexedProbs[i].second;
        float prob = indexedProbs[i].first;
        std::string name = getAppNameById(appId);
        results.push_back({name, prob});
    }
    
    return results;
}

} // namespace transformer
