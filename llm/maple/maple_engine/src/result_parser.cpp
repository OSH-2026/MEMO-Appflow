#include "result_parser.h"

#include <algorithm>
#include <cstdlib>
#include <map>
#include <regex>

namespace maple {

// Known MAPLE bridge category IDs to names. Small local models sometimes output
// "280 (75%)" or "App 110" instead of the category name.
static const std::map<int, std::string> ID_TO_CATEGORY = {
    {110, "Android Service IPC"},
    {115, "Display Composition"},
    {120, "Native Runtime Loading"},
    {130, "Framework Loading"},
    {140, "System Property Access"},
    {150, "APEX Runtime Loading"},
    {160, "Kernel Trace Setup"},
    {170, "Process State Inspection"},
    {180, "Database"},
    {190, "Dex/OAT Loading"},
    {200, "Cache/File Cache"},
    {210, "Config File Access"},
    {220, "Memory Management"},
    {230, "Other File Access"},
    {235, "Device/IPC Node Access"},
    {240, "Input Interaction"},
    {245, "Network IO"},
    {250, "Camera Service"},
    {255, "Payment/Security"},
    {260, "Media Codec"},
    {270, "Navigation/Location"},
    {280, "App Process Runtime"},
    {290, "Android System Services"},
    {300, "Power/Thermal Management"},
};

static std::string strip_think_tags(const std::string& raw) {
    std::string cleaned = raw;
    size_t pos = 0;
    while ((pos = cleaned.find("<think>")) != std::string::npos) {
        size_t end = cleaned.find("</think>", pos);
        if (end == std::string::npos) {
            cleaned.erase(pos);
            break;
        }
        cleaned.erase(pos, end - pos + 8);
    }
    return cleaned;
}

static std::string trim(const std::string& value) {
    const char* ws = " \t\n\r\"'*:-";
    size_t start = value.find_first_not_of(ws);
    if (start == std::string::npos) return "";
    size_t end = value.find_last_not_of(ws);
    return value.substr(start, end - start + 1);
}

static float clamp_probability(float value) {
    return std::max(0.0f, std::min(value, 1.0f));
}

static float parse_probability(const std::string& value) {
    return clamp_probability(static_cast<float>(std::atof(value.c_str()) / 100.0f));
}

static void uppercase_first(std::string& category) {
    if (!category.empty() && category[0] >= 'a' && category[0] <= 'z') {
        category[0] = category[0] - 'a' + 'A';
    }
}

static std::string category_from_id(int id) {
    auto it = ID_TO_CATEGORY.find(id);
    return it == ID_TO_CATEGORY.end() ? "" : it->second;
}

static bool ignored_trace_token(const std::string& value) {
    return value.rfind("MEMO_", 0) == 0 ||
           value == "MEMO_BINDER" ||
           value == "MEMO_OPENAT" ||
           value == "MEMO_RECVFROM" ||
           value == "MEMO_SENDTO" ||
           value == "MEMO_RECLAIM_BEGIN";
}

AppTypeResult ResultParser::parse_app_type(const std::string& raw) const {
    AppTypeResult result;
    result.raw_output = raw;

    const std::string text = strip_think_tags(raw);

    // Pattern 1: "Name (XX%)", "Name (XX)", or "App 110".
    {
        std::regex re(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,4})\s*\(\s*(\d+)\s*(?:%|records)?\s*\))");
        std::sregex_iterator it(text.begin(), text.end(), re);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string category = trim((*it)[1].str());
            if (category.empty() || ignored_trace_token(category)) continue;

            int value = std::atoi((*it)[2].str().c_str());
            std::string from_id = category_from_id(value);
            if (!from_id.empty() && (category == "App" || category.size() <= 3)) {
                category = from_id;
            }

            float prob = value >= 100 ? 1.0f : clamp_probability(value / 100.0f);
            uppercase_first(category);
            result.top_categories.emplace_back(category, prob);
        }
    }

    // Pattern 2: bare category id with a probability, e.g. "280 (75%)".
    if (result.top_categories.empty()) {
        std::regex re(R"(^\s*(\d+)\s*\(\s*(\d+(?:\.\d+)?)\s*%\s*\)\s*$)");
        std::smatch match;
        if (std::regex_search(text, match, re)) {
            std::string category = category_from_id(std::atoi(match[1].str().c_str()));
            if (!category.empty()) {
                result.top_categories.emplace_back(category, parse_probability(match[2].str()));
            }
        }
    }

    // Pattern 3: generic percent output such as "Communication app (70%)".
    if (result.top_categories.empty()) {
        std::regex re_pct(R"(\*?\*?(\w+(?:\s+\w+)?)\s+(?:app|category)\s*\(?(\d+(?:\.\d+)?)%\)?\*?\*?)");
        std::sregex_iterator it(text.begin(), text.end(), re_pct);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string category = (*it)[1].str();
            float prob = parse_probability((*it)[2].str());
            uppercase_first(category);
            result.top_categories.emplace_back(category, prob);
        }
    }

    // Pattern 4: "Category: 80%" or "Category 80%".
    if (result.top_categories.empty()) {
        std::regex re_any_pct(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,3})\s*(?:\(|:)?\s*(\d+(?:\.\d+)?)%\)?)");
        std::sregex_iterator it(text.begin(), text.end(), re_any_pct);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string category = trim((*it)[1].str());
            if (category.empty() || ignored_trace_token(category)) continue;
            float prob = parse_probability((*it)[2].str());
            uppercase_first(category);
            result.top_categories.emplace_back(category, prob);
        }
    }

    // Pattern 5: known category name without a percentage.
    if (result.top_categories.empty()) {
        for (const auto& kv : ID_TO_CATEGORY) {
            if (text.find(kv.second) != std::string::npos) {
                result.top_categories.emplace_back(kv.second, 0.8f);
                break;
            }
        }
    }

    // Pattern 6: markdown/simple app-category language.
    if (result.top_categories.empty()) {
        std::regex re_md(R"(\*\*?(\w+(?:\s+\w+)?)\s+(?:app|category)\*\*?)");
        std::sregex_iterator it(text.begin(), text.end(), re_md);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string category = (*it)[1].str();
            uppercase_first(category);
            result.top_categories.emplace_back(category, 0.0f);
        }
    }

    if (result.top_categories.empty()) {
        std::regex simple_re(R"(\b(\w+(?:\s+\w+)?)\s+(?:app|category)\b)");
        std::sregex_iterator it(text.begin(), text.end(), simple_re);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string category = (*it)[1].str();
            uppercase_first(category);
            result.top_categories.emplace_back(category, 0.0f);
        }
    }

    return result;
}

NextAppResult ResultParser::parse_next_app(const std::string& raw) const {
    NextAppResult result;
    result.raw_output = raw;
    result.reasoning = strip_think_tags(raw);

    // Only accept explicit "App X" output. A bare number may be a confidence,
    // trace count, or Binder code.
    std::regex re_app(R"([Aa]pp\s+(\d+))");
    std::smatch match;
    if (std::regex_search(result.reasoning, match, re_app)) {
        result.predicted_app_id = std::atoi(match[1].str().c_str());
    }

    return result;
}

} // namespace maple
