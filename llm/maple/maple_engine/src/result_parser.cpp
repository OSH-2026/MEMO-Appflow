#include "result_parser.h"
#include <regex>
#include <cstdlib>
#include <algorithm>
#include <map>

namespace maple {

// Known MAPLE bridge category IDs → names (reverse lookup for small-model
// outputs like "280 (75%)" where the model outputs the ID instead of the name).
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
    {250, "Camera Service"},
    {260, "Media Codec"},
    {270, "Navigation/Location"},
    {280, "App Process Runtime"},
    {290, "Android System Services"},
};

// Strip <think>...</think> blocks from reasoning models
static std::string strip_think_tags(const std::string& raw) {
    std::string cleaned = raw;
    size_t pos = 0;
    while ((pos = cleaned.find("<think>")) != std::string::npos) {
        size_t end = cleaned.find("</think>", pos);
        if (end == std::string::npos) {
            cleaned.erase(pos);
            break;
        }
        cleaned.erase(pos, end - pos + 8); // 8 = len("</think>")
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

static float parse_probability(const std::string& value) {
    float prob = std::atof(value.c_str()) / 100.0f;
    return std::max(0.0f, std::min(prob, 1.0f));
}

static void uppercase_first(std::string& category) {
    if (!category.empty() && category[0] >= 'a' && category[0] <= 'z') {
        category[0] = category[0] - 'a' + 'A';
    }
}

// Try to resolve an integer as a MAPLE category ID
static std::string category_from_id(int id) {
    auto it = ID_TO_CATEGORY.find(id);
    return (it != ID_TO_CATEGORY.end()) ? it->second : "";
}

AppTypeResult ResultParser::parse_app_type(const std::string& raw) const {
    AppTypeResult result;
    result.raw_output = raw;

    std::string text = strip_think_tags(raw);

    // ── Pattern 1: standard "Name (XX%)" or "Name (XX)" ──
    // Now also matches without % (small models often output "Name (110)" with ID).
    // Also handles "Name (count records)" by stripping the " records" suffix.
    {
        std::regex re(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,4})\s*\(\s*(\d+)\s*(?:%|records)?\s*\))");
        std::sregex_iterator it(text.begin(), text.end(), re);
        std::sregex_iterator end;
        for (; it != end; ++it) {
            std::string name = trim((*it)[1].str());
            if (name.empty() || name == "MEMO_BINDER" || name == "MEMO_OPENAT" || name == "MEMO_RECLAIM_BEGIN") continue;
            int num = std::atoi((*it)[2].str().c_str());
            // If num looks like a category ID (100-300 range), check reverse lookup.
            // But prefer the explicit name if it looks like a known category.
            std::string from_id = category_from_id(num);
            if (!from_id.empty() && (name == "App" || name.size() <= 3)) {
                // Output like "App 110" → use reverse lookup
                name = from_id;
            }
            float prob = (num >= 100) ? 1.0f : (float)num / 100.0f;
            prob = std::max(0.0f, std::min(prob, 1.0f));
            uppercase_first(name);
            result.top_categories.emplace_back(name, prob);
        }
    }

    // ── Pattern 2: "Number (XX%)" → reverse ID lookup ──
    // Small models sometimes output "280 (75%)" where 280 is the category ID.
    if (result.top_categories.empty()) {
        std::regex re(R"(^\s*(\d+)\s*\(\s*(\d+(?:\.\d+)?)\s*%\s*\)\s*$)");
        std::smatch m;
        if (std::regex_search(text, m, re)) {
            int id = std::atoi(m[1].str().c_str());
            std::string name = category_from_id(id);
            if (!name.empty()) {
                float prob = parse_probability(m[2].str());
                result.top_categories.emplace_back(name, prob);
            }
        }
    }

    // ── Pattern 3: "%" based patterns (original logic) ──
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

    if (result.top_categories.empty()) {
        std::regex re_any_pct(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,3})\s*(?:\(|:)?\s*(\d+(?:\.\d+)?)%\)?)");
        std::sregex_iterator sit(text.begin(), text.end(), re_any_pct);
        std::sregex_iterator end;
        for (; sit != end; ++sit) {
            std::string category = trim((*sit)[1].str());
            if (category.empty()) continue;
            float prob = parse_probability((*sit)[2].str());
            uppercase_first(category);
            result.top_categories.emplace_back(category, prob);
        }
    }

    // ── Pattern 4: bare category name without parenthetical (last resort) ──
    if (result.top_categories.empty()) {
        for (const auto& kv : ID_TO_CATEGORY) {
            if (text.find(kv.second) != std::string::npos) {
                result.top_categories.emplace_back(kv.second, 0.8f);
                break; // first match wins
            }
        }
    }

    // ── Pattern 5: bare markdown/simple word patterns ──
    if (result.top_categories.empty()) {
        std::regex re_md(R"(\*\*?(\w+(?:\s+\w+)?)\s+(?:app|category)\*\*?)");
        std::sregex_iterator sit(text.begin(), text.end(), re_md);
        std::sregex_iterator end;
        for (; sit != end; ++sit) {
            std::string category = (*sit)[1].str();
            uppercase_first(category);
            result.top_categories.emplace_back(category, 0.0f);
        }
    }

    if (result.top_categories.empty()) {
        std::regex simple_re(R"(\b(\w+(?:\s+\w+)?)\s+(?:app|category)\b)");
        std::sregex_iterator sit(text.begin(), text.end(), simple_re);
        std::sregex_iterator end;
        for (; sit != end; ++sit) {
            std::string category = (*sit)[1].str();
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

    // Only accept explicit "App X" output.  A bare number can be a confidence,
    // trace count, or Binder code, so it should not become a predicted app id.
    std::regex re_app(R"([Aa]pp\s+(\d+))");
    std::smatch match;
    if (std::regex_search(result.reasoning, match, re_app)) {
        result.predicted_app_id = std::atoi(match[1].str().c_str());
    }

    return result;
}

} // namespace maple
