#pragma once

#include <string>
#include <memory>

struct llama_model;
struct llama_context;

namespace maple {

// Thin C++ wrapper around llama.cpp C API for text generation.
class LlamaBackend {
public:
    struct Config {
        std::string model_path;
        int n_ctx = 2048;
        int n_threads = 4;
        float temperature = 0.7f;
        int max_tokens = 128;
        int top_k = 40;
        float top_p = 0.9f;
        float repeat_penalty = 1.1f;
    };

    explicit LlamaBackend(const Config& cfg);
    ~LlamaBackend();

    // Generate text from a prompt. Returns the generated string (detokenized).
    std::string generate(const std::string& prompt);

    bool is_loaded() const { return model_ != nullptr && ctx_ != nullptr; }

private:
    Config cfg_;
    llama_model* model_ = nullptr;
    llama_context* ctx_ = nullptr;

    bool create_context();
    void destroy_context();

    // Apply Qwen3.5 chat template to a user prompt (non-thinking mode).
    std::string apply_chat_template(const std::string& user_msg) const;
};

} // namespace maple
