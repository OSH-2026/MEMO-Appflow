#pragma once

#include "inferencer.h"
#include <string>
#include <vector>
#include <unordered_map>

namespace transformer {

// App 级别事件（对外接口）
// Hour is directly provided (not computed from time_minutes)
struct AppEvent {
    std::string appName;        // 应用名称/包名
    float startTimeMinutes;     // 开始时间（累积分钟，跨天递增）
    float endTimeMinutes;       // 结束时间（累积分钟）
    float startHourOfDay;       // 开始时刻的小时（0-24，直接从时间戳解析）
    float endHourOfDay;         // 结束时刻的小时（0-24，直接从时间戳解析）
    
    AppEvent(const std::string& name, float start, float end, float startHour, float endHour) 
        : appName(name), startTimeMinutes(start), endTimeMinutes(end),
          startHourOfDay(startHour), endHourOfDay(endHour) {}
};

// 预测结果
struct Prediction {
    std::string appName;        // 应用名称，或 "<unknown_id_X>" 占位符
    float probability;          // 概率
};

// TransformerPredictor（App 级别封装）
class TransformerPredictor {
public:
    TransformerPredictor(const std::string& weightsPath, 
                               const std::string& manifestPath);
    
    // 添加一个 App 事件（内部拆分为 2 个 tokens：打开+关闭）
    void addEvent(const AppEvent& event);
    
    // 批量添加事件
    void addEvents(const std::vector<AppEvent>& events);
    
    // 预测下一个 App，返回 topK 个（appName, probability）
    std::vector<Prediction> predict(int topK);
    
    // 清空所有状态（包括 ID 映射）
    void clear();
    
    // 获取当前 App 事件数（非 token 数）
    size_t getEventCount() const { return eventCount_; }
    
private:
    static constexpr size_t MAX_EVENTS = 1536;   // 最大 App 事件数
    static constexpr size_t HALF_EVENTS = 768;   // 半窗口事件数
    static constexpr int MAX_APP_IDS = 200;      // vocab size
    
    TransformerInferencer inferencer0_;
    TransformerInferencer inferencer1_;
    size_t eventCount_ = 0;  // App 事件计数
    
    // ID 映射表（动态分配，循环复用）
    std::unordered_map<std::string, int> nameToId_;  // appName → 0~199
    std::unordered_map<int, std::string> idToName_;  // 0~199 → appName
    int nextId_ = 0;  // 下一个待分配的 ID
    
    // 辅助函数
    int getOrCreateAppId(const std::string& appName);
    void addToken(TransformerInferencer& inf, int appId, int action, float timeMin, float hour);
    TransformerInferencer& getPrimaryInferencer();
    std::string getAppNameById(int appId);  // 未知返回 "<unknown_id_{id}>"
    
    // 根据 eventCount 判断当前使用哪个推理器
    bool shouldUseBothInferencers() const;
    bool shouldClearInferencer0() const;
    bool shouldClearInferencer1() const;
};

} // namespace transformer
