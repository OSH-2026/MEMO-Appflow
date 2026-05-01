#pragma once

#include "maple_types.h"
#include <memory>
#include <utility>

namespace maple {

class LlamaBackend;
class PromptBuilder;
class ResultParser;

// Main MAPLE inference engine.
// Encapsulates two-stage app prediction using an LLM backend.
class MAPLEEngine {
public:
    struct Config {
        std::string model_path;       // Path to .gguf file
        int n_ctx = 2048;             // Context size
        int n_threads = 4;            // CPU threads
        float temperature = 0.7f;
        int max_tokens = 128;
    };

    explicit MAPLEEngine(const Config& cfg);
    ~MAPLEEngine();

    // Disable copy, allow move
    MAPLEEngine(const MAPLEEngine&) = delete;
    MAPLEEngine& operator=(const MAPLEEngine&) = delete;
    MAPLEEngine(MAPLEEngine&&) noexcept;
    MAPLEEngine& operator=(MAPLEEngine&&) noexcept;

    // Set which features to include in prompts (bitmask of FeatureFlags).
    void set_feature_flags(uint32_t flags);
    uint32_t feature_flags() const;

    // Stage 1: Predict app type/category.
    AppTypeResult predict_app_type(const UserContext& ctx);

    // Stage 2: Predict specific app ID.
    NextAppResult predict_next_app(const UserContext& ctx, const AppTypeResult& stage1);

    // Convenience: run full two-stage pipeline.
    std::pair<AppTypeResult, NextAppResult> predict(const UserContext& ctx);

    // Get the prompt that would be sent to the model (for debugging/demo)
    std::string preview_app_type_prompt(const UserContext& ctx) const;
    std::string preview_next_app_prompt(const UserContext& ctx, const AppTypeResult& stage1) const;

    bool is_ready() const;

private:
    std::unique_ptr<LlamaBackend> backend_;
    std::unique_ptr<PromptBuilder> prompt_builder_;
    std::unique_ptr<ResultParser> parser_;
    uint32_t feature_flags_ = FeatureFlags::DEFAULT;
};

// Parse UserContext from a simple JSON string (exposed for demo/tests)
UserContext parse_user_context(const std::string& json);

// Serialize helpers (exposed for C API internals)
std::string json_get_string(const std::string& json, const std::string& key);
std::string serialize_app_type(const AppTypeResult& r);
std::string serialize_next_app(const NextAppResult& r);

} // namespace maple

// ============================================================================
// C API (for Python ctypes / JNI glue code)
// ============================================================================

extern "C" {

typedef void* maple_engine_t;

maple_engine_t maple_engine_create(const char* model_path,
                                   int n_ctx, int n_threads,
                                   float temperature, int max_tokens);
void maple_engine_destroy(maple_engine_t engine);
void maple_engine_set_flags(maple_engine_t engine, uint32_t flags);

// Predict app type; writes JSON string to out_buf. Returns 0 on success.
int maple_predict_app_type(maple_engine_t engine,
                           const char* context_json,
                           char* out_buf, size_t out_buf_size);

// Predict next app; writes JSON string to out_buf. Returns 0 on success.
int maple_predict_next_app(maple_engine_t engine,
                           const char* context_json,
                           const char* stage1_json,
                           char* out_buf, size_t out_buf_size);

} // extern "C"
