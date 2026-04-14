// TransformerPredictor 极简使用示例
// 展示：数据加载 → 初始化 → Warmup → 5次预测

#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <iomanip>

#include "transformer_predictor.h"
#include "json.hpp"

using json = nlohmann::json;
using namespace transformer;

// 从 JSON 加载事件
std::vector<AppEvent> loadEvents(const std::string& path) {
    std::vector<AppEvent> events;
    std::ifstream f(path);
    json j; f >> j;
    for (auto& item : j) {
        events.emplace_back(
            item["app_name"].get<std::string>(),
            item["start_min"].get<float>(),
            item["end_min"].get<float>(),
            item["start_hour"].get<float>(),
            item["end_hour"].get<float>()
        );
    }
    return events;
}

int main() {
    // ========== 硬编码配置 ==========
    // 假设在 build/ 目录运行，.. 指向项目根目录
    const std::string weightsPath = "../weights/weights.bin";
    const std::string manifestPath = "../weights/manifest.txt";
    const std::string dataPath = "../example.json";
    const int warmupEvents = 100;
    const int numPredictions = 5;
    const int topK = 5;
    
    // ========== 1. 加载数据 ==========
    auto events = loadEvents(dataPath);
    std::cout << "Loaded " << events.size() << " events\n\n";
    
    // ========== 2. 初始化预测器 ==========
    TransformerPredictor predictor(weightsPath, manifestPath);
    
    // ========== 3. Warmup ==========
    std::cout << "Warmup with " << warmupEvents << " events...\n";
    for (int i = 0; i < warmupEvents; i++) {
        predictor.addEvent(events[i]);
    }
    std::cout << "Done. Event count: " << predictor.getEventCount() << "\n\n";
    
    // ========== 4. 演示5次预测 ==========
    std::cout << std::string(50, '=') << "\n";
    std::cout << "演示 " << numPredictions << " 次 AddEvent + Predict\n";
    std::cout << std::string(50, '=') << "\n\n";
    
    for (int round = 0; round < numPredictions; round++) {
        int idx = warmupEvents + round;
        const auto& e = events[idx];
        
        // 展示输入结构
        std::cout << "--- Round " << (round + 1) << " ---\n";
        std::cout << "Input (AppEvent):\n";
        std::cout << "  appName:          \"" << e.appName << "\"\n";
        std::cout << "  startTimeMinutes: " << e.startTimeMinutes << "\n";
        std::cout << "  endTimeMinutes:   " << e.endTimeMinutes << "\n";
        std::cout << "  startHourOfDay:   " << e.startHourOfDay << "\n";
        std::cout << "  endHourOfDay:     " << e.endHourOfDay << "\n";
        
        // 添加事件
        predictor.addEvent(e);
        
        // 预测并展示输出结构
        auto preds = predictor.predict(topK);
        
        std::cout << "\nOutput (Top-" << topK << " Prediction):\n";
        for (int i = 0; i < (int)preds.size(); i++) {
            std::cout << "  [" << i << "] appName: \"" << preds[i].appName << "\""
                      << ", probability: " << std::fixed << std::setprecision(4) 
                      << preds[i].probability << "\n";
        }
        
        // 下一轮的 ground truth
        std::cout << "\nNext ground truth: \"" << events[idx + 1].appName << "\"\n";
        std::cout << "\n";
    }
    
    return 0;
}
