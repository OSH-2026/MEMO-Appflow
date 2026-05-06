#include "maple_engine.h"
#include "llama_backend.h"
#include "prompt_builder.h"
#include "result_parser.h"
#include <sstream>
#include <cstring>
#include <cstdlib>

namespace maple {

// Helper to parse simple JSON field values
std::string json_get_string(const std::string& json, const std::string& key) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "";
    pos = json.find(':', pos);
    if (pos == std::string::npos) return "";
    pos = json.find('"', pos);
    if (pos == std::string::npos) return "";
    size_t end = json.find('"', pos + 1);
    if (end == std::string::npos) return "";
    return json.substr(pos + 1, end - pos - 1);
}

static std::vector<std::string> json_get_string_array(const std::string& json, const std::string& key) {
    std::vector<std::string> result;
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return result;
    pos = json.find('[', pos);
    if (pos == std::string::npos) return result;
    size_t end = json.find(']', pos);
    if (end == std::string::npos) return result;
    std::string arr = json.substr(pos + 1, end - pos - 1);
    size_t start = 0;
    while (start < arr.size()) {
        size_t q1 = arr.find('"', start);
        if (q1 == std::string::npos) break;
        size_t q2 = arr.find('"', q1 + 1);
        if (q2 == std::string::npos) break;
        result.push_back(arr.substr(q1 + 1, q2 - q1 - 1));
        start = q2 + 1;
    }
    return result;
}

static std::vector<int> json_get_int_array(const std::string& json, const std::string& key) {
    std::vector<int> result;
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return result;
    pos = json.find('[', pos);
    if (pos == std::string::npos) return result;
    size_t end = json.find(']', pos);
    if (end == std::string::npos) return result;
    std::string arr = json.substr(pos + 1, end - pos - 1);
    std::stringstream ss(arr);
    std::string token;
    while (std::getline(ss, token, ',')) {
        // trim whitespace
        size_t s = token.find_first_not_of(" \t\n\r");
        size_t e = token.find_last_not_of(" \t\n\r");
        if (s != std::string::npos) {
            result.push_back(std::atoi(token.substr(s, e - s + 1).c_str()));
        }
    }
    return result;
}

static std::map<std::string, std::vector<int>> json_get_installed_apps(const std::string& json) {
    std::map<std::string, std::vector<int>> result;
    size_t pos = json.find("\"installed_apps\"");
    if (pos == std::string::npos) return result;
    pos = json.find('{', pos);
    if (pos == std::string::npos) return result;
    // Find matching closing brace
    int depth = 1;
    size_t end = pos + 1;
    while (end < json.size() && depth > 0) {
        if (json[end] == '{') ++depth;
        else if (json[end] == '}') --depth;
        ++end;
    }
    std::string obj = json.substr(pos + 1, end - pos - 2);

    size_t start = 0;
    while (start < obj.size()) {
        size_t q1 = obj.find('"', start);
        if (q1 == std::string::npos) break;
        size_t q2 = obj.find('"', q1 + 1);
        if (q2 == std::string::npos) break;
        std::string category = obj.substr(q1 + 1, q2 - q1 - 1);
        size_t b1 = obj.find('[', q2);
        if (b1 == std::string::npos) break;
        size_t b2 = obj.find(']', b1);
        if (b2 == std::string::npos) break;
        std::string arr = obj.substr(b1 + 1, b2 - b1 - 1);
        std::stringstream ss(arr);
        std::string token;
        while (std::getline(ss, token, ',')) {
            size_t s = token.find_first_not_of(" \t\n\r");
            size_t e = token.find_last_not_of(" \t\n\r");
            if (s != std::string::npos) {
                result[category].push_back(std::atoi(token.substr(s, e - s + 1).c_str()));
            }
        }
        start = b2 + 1;
    }
    return result;
}

// Parse UserContext from a simple JSON string
UserContext parse_user_context(const std::string& json) {
    UserContext ctx;
    ctx.historical_app_categories = json_get_string_array(json, "historical_app_categories");
    ctx.historical_app_ids = json_get_int_array(json, "historical_app_ids");
    ctx.prediction_time = json_get_string(json, "prediction_time");
    ctx.points_of_interest = json_get_string_array(json, "points_of_interest");
    ctx.installed_apps = json_get_installed_apps(json);
    ctx.user_age = json_get_string(json, "user_age");
    ctx.user_gender = json_get_string(json, "user_gender");
    ctx.system_evidence = json_get_string_array(json, "system_evidence");
    ctx.memory_pressure = json_get_string(json, "memory_pressure");
    ctx.scheduler_goal = json_get_string(json, "scheduler_goal");
    return ctx;
}

// Serialize AppTypeResult to JSON
std::string serialize_app_type(const AppTypeResult& r) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"raw_output\":\"";
    for (char c : r.raw_output) {
        if (c == '"' || c == '\\') oss << '\\';
        if (c == '\n') { oss << "\\n"; continue; }
        oss << c;
    }
    oss << "\",";
    oss << "\"top_categories\":[";
    for (size_t i = 0; i < r.top_categories.size(); ++i) {
        if (i > 0) oss << ",";
        oss << "{\"name\":\"" << r.top_categories[i].first << "\","
            << "\"prob\":" << r.top_categories[i].second << "}";
    }
    oss << "]}";
    return oss.str();
}

// Serialize NextAppResult to JSON
std::string serialize_next_app(const NextAppResult& r) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"predicted_app_id\":" << r.predicted_app_id << ",";
    oss << "\"reasoning\":\"";
    for (char c : r.reasoning) {
        if (c == '"' || c == '\\') oss << '\\';
        if (c == '\n') { oss << "\\n"; continue; }
        oss << c;
    }
    oss << "\",";
    oss << "\"raw_output\":\"";
    for (char c : r.raw_output) {
        if (c == '"' || c == '\\') oss << '\\';
        if (c == '\n') { oss << "\\n"; continue; }
        oss << c;
    }
    oss << "\"}";
    return oss.str();
}

MAPLEEngine::MAPLEEngine(const Config& cfg)
    : backend_(std::make_unique<LlamaBackend>(LlamaBackend::Config{
          cfg.model_path, cfg.n_ctx, cfg.n_threads,
          cfg.temperature, cfg.max_tokens})),
      prompt_builder_(std::make_unique<PromptBuilder>(FeatureFlags::DEFAULT)),
      parser_(std::make_unique<ResultParser>()) {}

MAPLEEngine::~MAPLEEngine() = default;
MAPLEEngine::MAPLEEngine(MAPLEEngine&&) noexcept = default;
MAPLEEngine& MAPLEEngine::operator=(MAPLEEngine&&) noexcept = default;

void MAPLEEngine::set_feature_flags(uint32_t flags) {
    feature_flags_ = flags;
    if (prompt_builder_) {
        prompt_builder_->set_flags(flags);
    }
}

uint32_t MAPLEEngine::feature_flags() const {
    return feature_flags_;
}

AppTypeResult MAPLEEngine::predict_app_type(const UserContext& ctx) {
    if (!is_ready()) return AppTypeResult{};
    std::string prompt = prompt_builder_->build_app_type_prompt(ctx);
    std::string output = backend_->generate(prompt);
    return parser_->parse_app_type(output);
}

NextAppResult MAPLEEngine::predict_next_app(const UserContext& ctx, const AppTypeResult& stage1) {
    if (!is_ready()) return NextAppResult{};
    if (!stage1.top_categories.empty()) {
        const auto it = ctx.installed_apps.find(stage1.top_categories[0].first);
        if (it != ctx.installed_apps.end() && it->second.size() == 1) {
            NextAppResult result;
            result.predicted_app_id = it->second[0];
            result.reasoning = "This user will use App " + std::to_string(result.predicted_app_id) + ".";
            result.raw_output = result.reasoning;
            return result;
        }
    }
    std::string prompt = prompt_builder_->build_next_app_prompt(ctx, stage1);
    std::string output = backend_->generate(prompt);
    return parser_->parse_next_app(output);
}

std::pair<AppTypeResult, NextAppResult> MAPLEEngine::predict(const UserContext& ctx) {
    AppTypeResult stage1 = predict_app_type(ctx);
    NextAppResult stage2 = predict_next_app(ctx, stage1);
    return {std::move(stage1), std::move(stage2)};
}

std::string MAPLEEngine::preview_app_type_prompt(const UserContext& ctx) const {
    if (!prompt_builder_) return "";
    return prompt_builder_->build_app_type_prompt(ctx);
}

std::string MAPLEEngine::preview_next_app_prompt(const UserContext& ctx, const AppTypeResult& stage1) const {
    if (!prompt_builder_) return "";
    return prompt_builder_->build_next_app_prompt(ctx, stage1);
}

bool MAPLEEngine::is_ready() const {
    return backend_ && backend_->is_loaded();
}

} // namespace maple

// ============================================================================
// C API Implementation
// ============================================================================

extern "C" {

maple_engine_t maple_engine_create(const char* model_path,
                                   int n_ctx, int n_threads,
                                   float temperature, int max_tokens) {
    if (!model_path) return nullptr;
    maple::MAPLEEngine::Config cfg;
    cfg.model_path = model_path;
    cfg.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cfg.n_threads = n_threads > 0 ? n_threads : 4;
    cfg.temperature = temperature >= 0.0f ? temperature : 0.7f;
    cfg.max_tokens = max_tokens > 0 ? max_tokens : 128;
    try {
        auto* engine = new maple::MAPLEEngine(cfg);
        if (!engine->is_ready()) {
            delete engine;
            return nullptr;
        }
        return engine;
    } catch (...) {
        return nullptr;
    }
}

void maple_engine_destroy(maple_engine_t engine) {
    if (engine) {
        delete static_cast<maple::MAPLEEngine*>(engine);
    }
}

void maple_engine_set_flags(maple_engine_t engine, uint32_t flags) {
    if (engine) {
        static_cast<maple::MAPLEEngine*>(engine)->set_feature_flags(flags);
    }
}

int maple_predict_app_type(maple_engine_t engine,
                           const char* context_json,
                           char* out_buf, size_t out_buf_size) {
    if (!engine || !context_json || !out_buf || out_buf_size == 0) return -1;
    try {
        maple::UserContext ctx = maple::parse_user_context(context_json);
        maple::AppTypeResult result = static_cast<maple::MAPLEEngine*>(engine)->predict_app_type(ctx);
        std::string json = maple::serialize_app_type(result);
        if (json.size() >= out_buf_size) {
            return -2; // buffer too small
        }
        std::memcpy(out_buf, json.c_str(), json.size() + 1);
        return 0;
    } catch (...) {
        return -3;
    }
}

int maple_predict_next_app(maple_engine_t engine,
                           const char* context_json,
                           const char* stage1_json,
                           char* out_buf, size_t out_buf_size) {
    if (!engine || !context_json || !out_buf || out_buf_size == 0) return -1;
    try {
        maple::UserContext ctx = maple::parse_user_context(context_json);
        maple::AppTypeResult stage1;
        if (stage1_json) {
            maple::ResultParser parser;
            stage1 = parser.parse_app_type(maple::json_get_string(stage1_json, "raw_output"));
        }
        maple::NextAppResult result = static_cast<maple::MAPLEEngine*>(engine)->predict_next_app(ctx, stage1);
        std::string json = maple::serialize_next_app(result);
        if (json.size() >= out_buf_size) {
            return -2;
        }
        std::memcpy(out_buf, json.c_str(), json.size() + 1);
        return 0;
    } catch (...) {
        return -3;
    }
}

int maple_build_app_type_prompt(maple_engine_t engine,
                                const char* context_json,
                                char* out_buf, size_t out_buf_size) {
    if (!engine || !context_json || !out_buf || out_buf_size == 0) return -1;
    try {
        maple::UserContext ctx = maple::parse_user_context(context_json);
        std::string prompt = static_cast<maple::MAPLEEngine*>(engine)->preview_app_type_prompt(ctx);
        if (prompt.size() >= out_buf_size) {
            return -2;
        }
        std::memcpy(out_buf, prompt.c_str(), prompt.size() + 1);
        return 0;
    } catch (...) {
        return -3;
    }
}

int maple_build_prompt_standalone(const char* context_json,
                                  char* out_buf, size_t out_buf_size) {
    if (!context_json || !out_buf || out_buf_size == 0) return -1;
    try {
        maple::UserContext ctx = maple::parse_user_context(context_json);
        maple::PromptBuilder builder(maple::FeatureFlags::ALL);
        std::string prompt = builder.build_app_type_prompt(ctx);
        if (prompt.size() >= out_buf_size) {
            return -2;
        }
        std::memcpy(out_buf, prompt.c_str(), prompt.size() + 1);
        return 0;
    } catch (...) {
        return -3;
    }
}

} // extern "C"
