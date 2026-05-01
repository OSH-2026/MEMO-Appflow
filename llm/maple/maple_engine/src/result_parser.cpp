#include "result_parser.h"
#include <regex>
#include <cstdlib>
#include <algorithm>

namespace maple {

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

AppTypeResult ResultParser::parse_app_type(const std::string& raw) const {
    AppTypeResult result;
    result.raw_output = raw;

    std::string text = strip_think_tags(raw);

    // Regex patterns to extract category names and percentages.
    // Matches patterns like:
    //   "communication app (70%)"
    //   "**Social app**"
    //   "Communication app"
    std::regex re_pct(R"(\*?\*?(\w+(?:\s+\w+)?)\s+(?:app|category)\s*\(?(\d+(?:\.\d+)?)%\)?\*?\*?)");
    std::sregex_iterator it(text.begin(), text.end(), re_pct);
    std::sregex_iterator end;

    for (; it != end; ++it) {
        std::string category = (*it)[1].str();
        float prob = parse_probability((*it)[2].str());
        uppercase_first(category);
        result.top_categories.emplace_back(category, prob);
    }

    // MEMO eBPF bridge categories are not always phrased as "X app".  Accept
    // outputs like "Android Service IPC (80%)" or "Display Composition: 20%".
    if (result.top_categories.empty()) {
        std::regex re_any_pct(R"(\b([A-Za-z][A-Za-z0-9_/\-]*(?:\s+[A-Za-z][A-Za-z0-9_/\-]*){0,3})\s*(?:\(|:)?\s*(\d+(?:\.\d+)?)%\)?)");
        std::sregex_iterator sit(text.begin(), text.end(), re_any_pct);
        for (; sit != end; ++sit) {
            std::string category = trim((*sit)[1].str());
            if (category.empty()) continue;
            float prob = parse_probability((*sit)[2].str());
            uppercase_first(category);
            result.top_categories.emplace_back(category, prob);
        }
    }

    // Also accept bold/italic markdown category names without percentages.
    if (result.top_categories.empty()) {
        std::regex re_md(R"(\*\*?(\w+(?:\s+\w+)?)\s+(?:app|category)\*\*?)");
        std::sregex_iterator sit(text.begin(), text.end(), re_md);
        for (; sit != end; ++sit) {
            std::string category = (*sit)[1].str();
            uppercase_first(category);
            result.top_categories.emplace_back(category, 0.0f);
        }
    }

    // Also accept a simple word + "app" or "category" phrase.
    if (result.top_categories.empty()) {
        std::regex simple_re(R"(\b(\w+(?:\s+\w+)?)\s+(?:app|category)\b)");
        std::sregex_iterator sit(text.begin(), text.end(), simple_re);
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
