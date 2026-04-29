#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace maple {

// Feature toggle flags (bitmask).
struct FeatureFlags {
    static constexpr uint32_t HISTORICAL_APP_CATEGORY = 0x01;
    static constexpr uint32_t PREDICTION_TIME         = 0x02;
    static constexpr uint32_t POINT_OF_INTEREST       = 0x04;
    static constexpr uint32_t HISTORICAL_APP_USAGE    = 0x08;
    static constexpr uint32_t INSTALLED_APPS          = 0x10;
    static constexpr uint32_t USER_DEMOGRAPHICS       = 0x20;
    static constexpr uint32_t ALL                     = 0x3F;
    static constexpr uint32_t DEFAULT                 = 0x1F; // all except demographics
};

// Input context for MAPLE prediction.  The first fields are the original MAPLE
// app-prediction contract; the final three fields are MEMO's eBPF bridge.
struct UserContext {
    std::vector<std::string> historical_app_categories;
    std::vector<int> historical_app_ids;
    std::string prediction_time;
    std::vector<std::string> points_of_interest;
    std::map<std::string, std::vector<int>> installed_apps;
    std::string user_age;
    std::string user_gender;
    std::vector<std::string> system_evidence;
    std::string memory_pressure;
    std::string scheduler_goal;
};

// Stage 1 app/resource category prediction result.
struct AppTypeResult {
    std::vector<std::pair<std::string, float>> top_categories;
    std::string raw_output;
};

// Stage 2 MAPLE candidate ID prediction result.
struct NextAppResult {
    int predicted_app_id = -1;
    std::string reasoning;
    std::string raw_output;
};

} // namespace maple
