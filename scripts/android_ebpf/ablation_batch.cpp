/** Batch ablation inference for MEMO-Appflow.
 *
 * Links against llama.cpp directly (not MAPLE PromptBuilder) so we can
 * use unbiased prompts that don't hardcode "Android Service IPC" as the
 * example answer.
 *
 * Build:
 *   cd llm/maple/llama.cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j16
 *   g++ -std=c++17 -O2 -I llm/maple/llama.cpp/include -I llm/maple/llama.cpp/ggml/include \
 *       scripts/android_ebpf/ablation_batch.cpp \
 *       llm/maple/llama.cpp/build/src/libllama.a \
 *       llm/maple/llama.cpp/build/common/libllama-common.a \
 *       llm/maple/llama.cpp/build/ggml/src/libggml.a \
 *       llm/maple/llama.cpp/build/ggml/src/libggml-base.a \
 *       llm/maple/llama.cpp/build/ggml/src/libggml-cpu.a \
 *       -o ablation_batch -lpthread -ldl -fopenmp
 */

#include "llama.h"
#include "common.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Category list (must match CATEGORY_IDS in Python)
// ---------------------------------------------------------------------------
static const std::vector<std::string> ALL_CATEGORIES = {
    "Android Service IPC", "Display Composition",   "Native Runtime Loading",
    "Framework Loading",   "System Property Access", "APEX Runtime Loading",
    "Kernel Trace Setup",  "Process State Inspection", "Database",
    "Dex/OAT Loading",     "Cache/File Cache",       "Config File Access",
    "Memory Management",   "Other File Access",      "Device/IPC Node Access",
    "Input Interaction",   "Camera Service",         "Media Codec",
    "Navigation/Location", "App Process Runtime",    "Android System Services",
};

// ---------------------------------------------------------------------------
// Minimal JSON helpers (avoid depending on nlohmann etc.)
// ---------------------------------------------------------------------------
static std::string json_escape(const std::string& s) {
    std::string out;
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;
        }
    }
    return out;
}

static std::string json_get_string(const std::string& json, const std::string& key) {
    std::string k = "\"" + key + "\"";
    size_t pos = json.find(k);
    if (pos == std::string::npos) return "";
    pos = json.find(':', pos);
    if (pos == std::string::npos) return "";
    pos = json.find('"', pos);
    if (pos == std::string::npos) return "";
    size_t end = json.find('"', pos + 1);
    if (end == std::string::npos) return "";
    return json.substr(pos + 1, end - pos - 1);
}

static std::vector<std::string> json_get_string_array(const std::string& json,
                                                       const std::string& key) {
    std::vector<std::string> result;
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return result;
    pos = json.find('[', pos);
    if (pos == std::string::npos) return result;
    size_t end = json.find(']', pos);
    if (end == std::string::npos) return result;
    std::string arr = json.substr(pos + 1, end - pos - 1);
    size_t cursor = 0;
    while (cursor < arr.size()) {
        size_t q1 = arr.find('"', cursor);
        if (q1 == std::string::npos) break;
        size_t q2 = arr.find('"', q1 + 1);
        if (q2 == std::string::npos) break;
        result.push_back(arr.substr(q1 + 1, q2 - q1 - 1));
        cursor = q2 + 1;
    }
    return result;
}

static bool json_get_bool(const std::string& json, const std::string& key, bool def) {
    size_t pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return def;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return def;
    pos = json.find_first_not_of(" \t\n\r", pos + 1);
    if (pos == std::string::npos) return def;
    return json.compare(pos, 4, "true") == 0;
}

// ---------------------------------------------------------------------------
// Read entire file
// ---------------------------------------------------------------------------
static std::string read_file(const std::string& path) {
    std::ifstream f(path);
    if (!f) {
        std::cerr << "ERROR: cannot open " << path << "\n";
        return "";
    }
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

// ---------------------------------------------------------------------------
// Batch entry format (JSONL written by ablation_runner.py)
// ---------------------------------------------------------------------------
struct BatchEntry {
    std::string config_id;
    std::string sample_id;
    std::string context_json;
    std::string gt_category;
};

static std::vector<BatchEntry> load_batch(const std::string& path) {
    std::vector<BatchEntry> entries;
    std::ifstream f(path);
    if (!f) return entries;
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty()) continue;
        BatchEntry e;
        e.config_id = json_get_string(line, "config_id");
        e.sample_id = json_get_string(line, "sample_id");
        e.gt_category = json_get_string(line, "gt_category");
        // context_json is the full "context" object as a JSON string;
        // we store the whole line and extract later
        e.context_json = line;
        entries.push_back(e);
    }
    return entries;
}

// Extract a specific field's JSON object from the batch line
static std::string extract_context_json(const std::string& line) {
    size_t pos = line.find("\"context\"");
    if (pos == std::string::npos) return "{}";
    pos = line.find('{', pos);
    if (pos == std::string::npos) return "{}";
    int depth = 1;
    size_t end = pos + 1;
    while (end < line.size() && depth > 0) {
        if (line[end] == '{') ++depth;
        else if (line[end] == '}') --depth;
        ++end;
    }
    // The context value is a JSON string, so it's doubly encoded.
    // Extract the raw string first.
    return line.substr(pos, end - pos);
}

// ---------------------------------------------------------------------------
// Build an unbiased prompt directly from context fields
// ---------------------------------------------------------------------------

static std::string build_stage1_prompt(const std::string& context_json) {
    // Parse fields we need
    auto cats  = json_get_string_array(context_json, "historical_app_categories");
    auto ids   = json_get_string_array(context_json, "historical_app_ids");
    auto hints = json_get_string_array(context_json, "points_of_interest");
    auto ev    = json_get_string_array(context_json, "system_evidence");
    std::string mp      = json_get_string(context_json, "memory_pressure");
    std::string sched   = json_get_string(context_json, "scheduler_goal");
    std::string pred_t  = json_get_string(context_json, "prediction_time");

    // Build a clean, category-neutral prompt
    std::ostringstream oss;

    // System instruction
    oss << "You are an Android system evidence analyzer. "
        << "Below is eBPF evidence from an Android device. "
        << "Your task: identify the DOMINANT resource-demand category based on event counts.\n\n";

    // Context
    oss << "Context:\n";

    // Evidence is the main signal
    if (!ev.empty()) {
        oss << "System evidence:\n";
        for (const auto& line : ev) {
            oss << "  " << line << "\n";
        }
    }

    if (!mp.empty()) {
        oss << "Memory pressure: " << mp << "\n";
    }

    if (!cats.empty()) {
        oss << "Top categories detected: ";
        for (size_t i = 0; i < cats.size(); ++i) {
            if (i) oss << ", ";
            oss << cats[i];
        }
        oss << "\n";
    }

    if (!hints.empty()) {
        oss << "System hints: ";
        for (size_t i = 0; i < hints.size(); ++i) {
            if (i) oss << ", ";
            oss << hints[i];
        }
        oss << "\n";
    }

    if (!pred_t.empty()) {
        oss << "Time: " << pred_t << "\n";
    }

    // Explicit instruction with ALL valid categories
    oss << "\n";
    oss << "Choose exactly ONE category from this list that best matches "
        << "the dominant resource demand in the evidence:\n";
    for (const auto& cat : ALL_CATEGORIES) {
        oss << "  - " << cat << "\n";
    }
    oss << "\n";
    oss << "Output ONLY the category name, nothing else. Do NOT output percentages, "
        << "confidence values, explanations, or markdown. Just the category name.\n";
    oss << "Example: Cache/File Cache\n\n";
    oss << "Answer: ";

    return oss.str();
}

// ---------------------------------------------------------------------------
// Tokenize prompt, run inference, get output
// ---------------------------------------------------------------------------
struct ModelRunner {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_threads = 8;
    int max_tokens = 32;

    bool load(const std::string& model_path) {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;

        model = llama_model_load(model_path.c_str(), mparams);
        if (!model) {
            std::cerr << "Failed to load model: " << model_path << "\n";
            return false;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = 2048;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;

        ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            std::cerr << "Failed to create context\n";
            llama_model_free(model);
            model = nullptr;
            return false;
        }

        vocab = llama_model_get_vocab(model);
        return true;
    }

    ~ModelRunner() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }

    std::string generate(const std::string& prompt) {
        if (!ctx || !model) return "";

        // Tokenize
        std::vector<llama_token> tokens;
        tokens.resize(prompt.size() + 16);
        int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                       tokens.data(), tokens.size(), true, false);
        if (n_tokens < 0) {
            n_tokens = -n_tokens;
            tokens.resize(n_tokens);
            llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                          tokens.data(), tokens.size(), true, false);
        } else {
            tokens.resize(n_tokens);
        }

        // Create sampler
        auto sparams = llama_sampler_chain_default_params();
        auto sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        // Eval the prompt
        int n_past = 0;
        size_t batch_size = 512;

        for (size_t i = 0; i < tokens.size(); i += batch_size) {
            size_t n = std::min(batch_size, tokens.size() - i);
            llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
            if (llama_decode(ctx, batch) != 0) {
                std::cerr << "llama_decode failed\n";
                llama_sampler_free(sampler);
                return "";
            }
            n_past += n;
        }

        // Generate
        std::string result;
        for (int i = 0; i < max_tokens; ++i) {
            llama_token token = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) break;

            char buf[128];
            int len = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
            if (len > 0) result.append(buf, len);

            llama_batch batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx, batch) != 0) break;
            n_past++;
        }

        llama_sampler_free(sampler);

        // Trim whitespace
        while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' '))
            result.pop_back();
        while (!result.empty() && (result.front() == '\n' || result.front() == '\r' || result.front() == ' '))
            result.erase(0, 1);

        return result;
    }
};

// ---------------------------------------------------------------------------
// Match model output to the closest known category
// ---------------------------------------------------------------------------
static std::string match_category(const std::string& raw, int* score_out = nullptr) {
    // Exact match
    for (const auto& cat : ALL_CATEGORIES) {
        if (raw == cat) {
            if (score_out) *score_out = 100;
            return cat;
        }
    }

    // Case-insensitive exact match
    std::string lower = raw;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    for (const auto& cat : ALL_CATEGORIES) {
        std::string cl = cat;
        std::transform(cl.begin(), cl.end(), cl.begin(), ::tolower);
        if (lower == cl) {
            if (score_out) *score_out = 95;
            return cat;
        }
    }

    // Substring match (longest wins)
    int best_score = 0;
    std::string best_cat;
    for (const auto& cat : ALL_CATEGORIES) {
        std::string cl = cat;
        std::transform(cl.begin(), cl.end(), cl.begin(), ::tolower);
        if (lower.find(cl) != std::string::npos && (int)cat.size() > best_score) {
            best_score = (int)cat.size();
            best_cat = cat;
        }
    }
    if (score_out) *score_out = best_score > 0 ? 80 : 0;
    return best_cat.empty() ? raw : best_cat;
}

// ---------------------------------------------------------------------------
int main(int argc, char** argv) {
    std::string model_path;
    std::string batch_path;
    int n_threads = 8;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--model") == 0 && i + 1 < argc) {
            model_path = argv[++i];
        } else if (std::strcmp(argv[i], "--batch") == 0 && i + 1 < argc) {
            batch_path = argv[++i];
        } else if (std::strcmp(argv[i], "--threads") == 0 && i + 1 < argc) {
            n_threads = std::atoi(argv[++i]);
        }
    }

    if (model_path.empty() || batch_path.empty()) {
        std::cerr << "Usage: ablation_batch --model <gguf> --batch <jsonl> [--threads N]\n";
        return 1;
    }

    // Load model
    std::cerr << "Loading model: " << model_path << "\n";
    ModelRunner runner;
    runner.n_threads = n_threads;
    if (!runner.load(model_path)) {
        return 1;
    }
    std::cerr << "Model loaded.\n";

    // Load batch
    std::cerr << "Loading batch: " << batch_path << "\n";
    auto entries = load_batch(batch_path);
    std::cerr << "Entries: " << entries.size() << "\n";

    // Process each entry
    std::cout << "[\n";
    bool first_output = true;

    time_t start_time = time(nullptr);

    for (size_t i = 0; i < entries.size(); ++i) {
        auto& entry = entries[i];

        // Extract context from the batch entry
        std::string ctx_json = extract_context_json(entry.context_json);
        // The context is a JSON string value (double-encoded), so parse it again
        // Actually, let's just use the raw context from the line

        // Build prompt from the context fields embedded in context_json
        std::string prompt = build_stage1_prompt(ctx_json);

        auto t0 = time(nullptr);
        std::string raw = runner.generate(prompt);
        auto t1 = time(nullptr);
        int latency_ms = (int)((t1 - t0) * 1000);

        std::string matched = match_category(raw);
        bool correct = (matched == entry.gt_category);

        // Build JSON output line
        std::ostringstream out;
        out << (first_output ? "" : ",\n");
        out << "  {\"config_id\":\"" << entry.config_id << "\"";
        out << ",\"sample_id\":\"" << entry.sample_id << "\"";
        out << ",\"gt_category\":\"" << json_escape(entry.gt_category) << "\"";
        out << ",\"predicted\":\"" << json_escape(matched) << "\"";
        out << ",\"raw_output\":\"" << json_escape(raw) << "\"";
        out << ",\"correct\":" << (correct ? "true" : "false");
        out << ",\"latency_ms\":" << latency_ms << "}";
        std::cout << out.str();

        first_output = false;

        if ((i + 1) % 10 == 0 || i == entries.size() - 1) {
            auto elapsed = time(nullptr) - start_time;
            std::cerr << "\rProgress: " << (i + 1) << "/" << entries.size()
                      << " (" << ((i + 1) * 100 / entries.size()) << "%)"
                      << " elapsed=" << elapsed << "s" << std::flush;
        }
    }
    std::cout << "\n]\n";
    std::cerr << "\nDone.\n";

    return 0;
}
