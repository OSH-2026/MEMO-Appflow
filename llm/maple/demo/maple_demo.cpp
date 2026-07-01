#include "maple_engine.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <string>
#include <cstring>
#include <cctype>
#include <vector>

static std::string read_file(const std::string& path) {
    std::ifstream f(path);
    if (!f) return "";
    std::ostringstream ss;
    ss << f.rdbuf();
    f.close();
    return ss.str();
}

static std::string extract_context_json(const std::string& scenario) {
    size_t pos = scenario.find("\"context\"");
    if (pos == std::string::npos) return "";
    pos = scenario.find('{', pos);
    if (pos == std::string::npos) return "";
    int depth = 1;
    size_t end = pos + 1;
    while (end < scenario.size() && depth > 0) {
        if (scenario[end] == '{') ++depth;
        else if (scenario[end] == '}') --depth;
        ++end;
    }
    return scenario.substr(pos, end - pos);
}

static std::string extract_field(const std::string& json, const std::string& key) {
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

static void print_banner(const std::string& text) {
    std::cout << "\n" << std::string(60, '=') << "\n";
    std::cout << "  " << text << "\n";
    std::cout << std::string(60, '=') << "\n";
}

static void print_stage(const std::string& title) {
    std::cout << "\n>>> " << title << "\n";
    std::cout << std::string(60, '-') << "\n";
}

static bool is_ebpf_context(const maple::UserContext& ctx) {
    return !ctx.system_evidence.empty() || !ctx.memory_pressure.empty() || !ctx.scheduler_goal.empty();
}

static int extract_int_field(const std::string& json, const std::string& key, int fallback) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return fallback;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return fallback;
    ++pos;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) ++pos;
    size_t end = pos;
    while (end < json.size() && std::isdigit(static_cast<unsigned char>(json[end]))) ++end;
    if (end == pos) return fallback;
    return std::atoi(json.substr(pos, end - pos).c_str());
}

static std::vector<std::string> compact_action_table(int bit_count) {
    static const char* actions[] = {
        "0 warm_launch_top1",
        "1 warm_launch_top2_if_idle",
        "2 trim_memory_low_priority_apps",
        "3 kill_selected_background_package",
        "4 drop_cache_if_critical_memory",
        "5 refresh_network_stats",
        "6 refresh_service_manager",
        "7 reduce_prewarm_when_display_busy",
        "8 skip_camera_prewarm_when_thermal_high",
    };
    std::vector<std::string> result;
    const int total = std::min(bit_count, static_cast<int>(sizeof(actions) / sizeof(actions[0])));
    for (int i = 0; i < total; ++i) result.emplace_back(actions[i]);
    return result;
}

static std::string first_bits(const std::string& text, int bit_count) {
    if (bit_count <= 0) return "";
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] != '0' && text[i] != '1') continue;
        std::string bits;
        size_t j = i;
        while (j < text.size() && (text[j] == '0' || text[j] == '1')) {
            bits.push_back(text[j]);
            ++j;
        }
        if (static_cast<int>(bits.size()) == bit_count) return bits;
        if (static_cast<int>(bits.size()) > bit_count) return bits.substr(0, bit_count);
        if (!bits.empty()) {
            while (static_cast<int>(bits.size()) < bit_count) bits.push_back('0');
            return bits;
        }
        i = j;
    }
    return "";
}

static std::string json_escape(const std::string& value) {
    std::ostringstream oss;
    for (char c : value) {
        if (c == '"' || c == '\\') oss << '\\';
        if (c == '\n') { oss << "\\n"; continue; }
        if (c == '\r') continue;
        oss << c;
    }
    return oss.str();
}

static std::string build_scheduler_prompt(const maple::UserContext& ctx,
                                          const maple::AppTypeResult& stage1,
                                          const maple::NextAppResult& stage2,
                                          int bit_count) {
    std::ostringstream oss;
    oss << "Choose MEMO scheduler_bits for the next 3 minutes.\n";
    oss << "Output exactly " << bit_count << " characters, only 0 or 1. No JSON. No words.\n";
    oss << "Bit meaning: 1 execute, 0 skip.\n";
    oss << "If network evidence is active, bit5 should be 1. If service/app switching is active, bit6 should be 1. If UI/display is busy, bit7 should be 1. If memory is normal, bit0 may be 1. If memory is high, bits2 or 4 may be 1.\n";
    oss << "Actions:\n";
    for (const auto& action : compact_action_table(bit_count)) {
        oss << "- " << action << "\n";
    }
    if (!stage1.top_categories.empty()) oss << "Category: " << stage1.top_categories[0].first << ".\n";
    if (stage2.predicted_app_id >= 0) oss << "App id: " << stage2.predicted_app_id << ".\n";
    if (!ctx.memory_pressure.empty()) oss << "Memory: " << ctx.memory_pressure << ".\n";
    if (!ctx.points_of_interest.empty()) oss << "Hints: " << ctx.points_of_interest.front() << ".\n";
    oss << "Evidence:\n";
    size_t emitted = 0;
    for (const auto& evidence : ctx.system_evidence) {
        if (emitted >= 4) break;
        oss << "- " << evidence << "\n";
        ++emitted;
    }
    oss << "Bits:\n";
    return oss.str();
}

int main(int argc, char** argv) {
    std::string model_path = "models/Qwen3.5-0.8B-Q4_K_M.gguf";
    std::string scenarios_path = "examples/scenarios.json";
    bool stage1_only = false;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--model") == 0 && i + 1 < argc) {
            model_path = argv[++i];
        } else if (std::strcmp(argv[i], "--scenarios") == 0 && i + 1 < argc) {
            scenarios_path = argv[++i];
        } else if (std::strcmp(argv[i], "--stage1-only") == 0) {
            stage1_only = true;
        }
    }

    print_banner("MAPLE Inference Demo");
    std::cout << "Model:     " << model_path << "\n";
    std::cout << "Scenarios: " << scenarios_path << "\n";

    std::string scenarios_json = read_file(scenarios_path);
    if (scenarios_json.empty()) {
        std::cerr << "Failed to read scenarios file.\n";
        return 1;
    }

    // Extract the first scenario only
    size_t pos = scenarios_json.find("\"id\"");
    if (pos == std::string::npos) {
        std::cerr << "No scenarios found.\n";
        return 1;
    }

    size_t obj_start = scenarios_json.find_last_of('{', pos);
    int depth = 1;
    size_t obj_end = obj_start + 1;
    while (obj_end < scenarios_json.size() && depth > 0) {
        if (scenarios_json[obj_end] == '{') ++depth;
        else if (scenarios_json[obj_end] == '}') --depth;
        ++obj_end;
    }

    std::string scenario = scenarios_json.substr(obj_start, obj_end - obj_start);
    std::string id = extract_field(scenario, "id");
    std::string desc = extract_field(scenario, "description");
    std::string ctx_json = extract_context_json(scenario);

    if (id.empty() || ctx_json.empty()) {
        std::cerr << "Failed to parse first scenario.\n";
        return 1;
    }

    maple::UserContext ctx = maple::parse_user_context(ctx_json);
    bool ebpf_context = is_ebpf_context(ctx);

    maple::MAPLEEngine::Config cfg;
    cfg.model_path = model_path;
    cfg.n_ctx = ebpf_context ? 1024 : 768;
    cfg.n_threads = 4;
    cfg.temperature = 0.0f;
    cfg.max_tokens = ebpf_context ? 96 : 24;

    maple::MAPLEEngine engine(cfg);
    if (!engine.is_ready()) {
        std::cerr << "Failed to initialize MAPLE engine.\n";
        return 1;
    }
    engine.set_feature_flags(maple::FeatureFlags::ALL);

    print_banner("Scenario: " + id);
    std::cout << "Description: " << desc << "\n";

    // ============================================================
    // STAGE 1: App Type Prediction (ATP)
    // ============================================================
    print_stage("Stage 1: App Type Prediction (ATP)");

    std::cout << "[1] INPUT INFO:\n";
    std::cout << "    Historical app categories: ";
    for (size_t i = 0; i < ctx.historical_app_categories.size(); ++i) {
        if (i) std::cout << ", ";
        std::cout << ctx.historical_app_categories[i];
    }
    std::cout << "\n";
    std::cout << "    Historical app IDs:        ";
    for (size_t i = 0; i < ctx.historical_app_ids.size(); ++i) {
        if (i) std::cout << ", ";
        std::cout << ctx.historical_app_ids[i];
    }
    std::cout << "\n";
    std::cout << "    Prediction time:           " << ctx.prediction_time << "\n";
    std::cout << "    Points of interest:        ";
    for (size_t i = 0; i < ctx.points_of_interest.size(); ++i) {
        if (i) std::cout << ", ";
        std::cout << ctx.points_of_interest[i];
    }
    std::cout << "\n";
    std::cout << (ebpf_context ? "    MAPLE candidate IDs:\n" : "    Installed apps:\n");
    for (const auto& kv : ctx.installed_apps) {
        std::cout << "      - " << kv.first << ": ";
        for (size_t i = 0; i < kv.second.size(); ++i) {
            if (i) std::cout << ", ";
            std::cout << kv.second[i];
        }
        std::cout << "\n";
    }
    if (!ctx.user_age.empty()) {
        std::cout << "    User age:                  " << ctx.user_age << "\n";
        std::cout << "    User gender:               " << ctx.user_gender << "\n";
    }
    if (!ctx.memory_pressure.empty()) {
        std::cout << "    eBPF memory pressure:      " << ctx.memory_pressure << "\n";
    }
    if (!ctx.system_evidence.empty()) {
        std::cout << "    Low-level eBPF evidence:\n";
        for (const auto& evidence : ctx.system_evidence) {
            std::cout << "      - " << evidence << "\n";
        }
    }
    if (!ctx.scheduler_goal.empty()) {
        std::cout << "    Scheduler goal:            " << ctx.scheduler_goal << "\n";
    }

    std::string stage1_prompt = engine.preview_app_type_prompt(ctx);
    std::cout << "\n[2] PROMPT sent to model:\n";
    std::cout << stage1_prompt << "\n";
    std::cout << "[prompt_chars] " << stage1_prompt.size() << "\n";

    auto stage1 = engine.predict_app_type(ctx);

    std::cout << "\n[3] MODEL RAW OUTPUT:\n";
    std::cout << "    \"" << stage1.raw_output << "\"\n";

    std::cout << "\n[4] PARSED RESULT:\n";
    if (!stage1.top_categories.empty()) {
        for (const auto& cat : stage1.top_categories) {
            std::cout << "    -> Predicted category: \"" << cat.first << "\"";
            if (cat.second > 0.0f) {
                std::cout << "  (confidence: " << (cat.second * 100) << "%)";
            }
            std::cout << "\n";
        }
    } else {
        std::cout << "    (no category parsed)\n";
    }

    if (stage1_only) {
        print_banner("FINAL PREDICTION SUMMARY");
        std::cout << "  Scenario:  " << desc << "\n";
        if (!stage1.top_categories.empty()) {
            std::cout << "  App Type:  \"" << stage1.top_categories[0].first << "\"\n";
        }
        std::cout << "  Stage 2:   skipped by --stage1-only for device-side latency\n";
        std::cout << std::string(60, '=') << "\n";
        return 0;
    }

    // ============================================================
    // STAGE 2: Next App Prediction (NTP)
    // ============================================================
    print_stage("Stage 2: Next App Prediction (NTP)");

    std::cout << "[1] INPUT INFO:\n";
    std::cout << "    Stage 1 predicted category:";
    if (!stage1.top_categories.empty()) {
        std::cout << stage1.top_categories[0].first;
        if (stage1.top_categories[0].second > 0.0f) {
            std::cout << " (" << (stage1.top_categories[0].second * 100) << "%)";
        }
    }
    std::cout << "\n";
    std::cout << "    Historical app IDs:        ";
    for (size_t i = 0; i < ctx.historical_app_ids.size(); ++i) {
        if (i) std::cout << ", ";
        std::cout << ctx.historical_app_ids[i];
    }
    std::cout << "\n";
    std::cout << (ebpf_context ? "    MAPLE candidate IDs:\n" : "    Installed apps:\n");
    for (const auto& kv : ctx.installed_apps) {
        std::cout << "      - " << kv.first << ": ";
        for (size_t i = 0; i < kv.second.size(); ++i) {
            if (i) std::cout << ", ";
            std::cout << kv.second[i];
        }
        std::cout << "\n";
    }

    std::string stage2_prompt = engine.preview_next_app_prompt(ctx, stage1);
    std::cout << "\n[2] PROMPT sent to model:\n";
    std::cout << stage2_prompt << "\n";
    std::cout << "[prompt_chars] " << stage2_prompt.size() << "\n";

    auto stage2 = engine.predict_next_app(ctx, stage1);

    std::cout << "\n[3] MODEL RAW OUTPUT:\n";
    std::cout << "    \"" << stage2.raw_output << "\"\n";

    std::cout << "\n[4] PARSED RESULT:\n";
    if (stage2.predicted_app_id >= 0) {
        std::cout << "    -> Predicted specific app: App " << stage2.predicted_app_id << "\n";
    } else {
        std::cout << "    (no app ID parsed)\n";
    }

    std::string scheduler_bits;
    std::string scheduler_raw;
    std::string scheduler_prompt;
    if (ebpf_context) {
        print_stage("Stage 3: Scheduler Decision (SDP)");
        const int bit_count = extract_int_field(ctx_json, "scheduler_bit_count", 9);
        scheduler_prompt = build_scheduler_prompt(ctx, stage1, stage2, bit_count);
        std::cout << "[1] INPUT INFO:\n";
        std::cout << "    Scheduler bit count:       " << bit_count << "\n";
        std::cout << "    Fixed action table:\n";
        for (const auto& action : compact_action_table(bit_count)) {
            std::cout << "      - " << action << "\n";
        }
        std::cout << "\n[2] PROMPT sent to model:\n";
        std::cout << scheduler_prompt << "\n";
        std::cout << "[prompt_chars] " << scheduler_prompt.size() << "\n";
        scheduler_raw = engine.generate_text(scheduler_prompt);
        scheduler_bits = first_bits(scheduler_raw, bit_count);
        std::cout << "\n[3] MODEL RAW OUTPUT:\n";
        std::cout << "    \"" << scheduler_raw << "\"\n";
        std::cout << "\n[4] PARSED RESULT:\n";
        if (!scheduler_bits.empty()) {
            std::cout << "    -> scheduler_bits: " << scheduler_bits << "\n";
        } else {
            std::cout << "    (no valid scheduler_bits parsed)\n";
        }
        std::cout << "\nMAPLE_STRUCTURED_OUTPUT:\n";
        std::cout << "{\"top3_apps\":[\"App " << stage2.predicted_app_id << "\"],"
                  << "\"pressure_analysis\":\"" << json_escape(ctx.memory_pressure) << "\","
                  << "\"scheduler_bits\":\"" << scheduler_bits << "\","
                  << "\"scheduler_plan\":[],"
                  << "\"raw_scheduler_output\":\"" << json_escape(scheduler_raw) << "\"}"
                  << "\n";
    }

    // ============================================================
    // FINAL SUMMARY
    // ============================================================
    print_banner("FINAL PREDICTION SUMMARY");
    std::cout << "  Scenario:  " << desc << "\n";
    if (!stage1.top_categories.empty()) {
        std::cout << "  App Type:  \"" << stage1.top_categories[0].first << "\"\n";
    }
    if (stage2.predicted_app_id >= 0) {
        std::cout << "  App ID:    " << stage2.predicted_app_id << "\n";
    }
    std::cout << std::string(60, '=') << "\n";

    return 0;
}
