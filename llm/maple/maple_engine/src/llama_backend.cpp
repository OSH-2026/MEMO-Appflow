#include "llama_backend.h"
#include "llama.h"
#include <cstring>
#include <vector>
#include <iostream>

namespace maple {

// Silent log callback: suppress all llama.cpp/ggml console output
static void silent_log_callback(enum ggml_log_level /*level*/, const char * /*text*/, void * /*user_data*/) {
    // do nothing
}

LlamaBackend::LlamaBackend(const Config& cfg) : cfg_(cfg) {
    llama_log_set(silent_log_callback, nullptr);
    ggml_backend_load_all();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only for portability

    model_ = llama_model_load_from_file(cfg.model_path.c_str(), mparams);
    if (!model_) {
        std::cerr << "[MAPLE] Failed to load model: " << cfg.model_path << std::endl;
        return;
    }

    if (!create_context()) {
        llama_model_free(model_);
        model_ = nullptr;
        return;
    }

    std::cerr << "[MAPLE] Model loaded successfully." << std::endl;
}

bool LlamaBackend::create_context() {
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = cfg_.n_ctx;
    cparams.n_batch = cfg_.n_ctx;
    cparams.n_ubatch = std::min(cfg_.n_ctx, 256);
    cparams.n_threads = cfg_.n_threads;
    cparams.n_threads_batch = cfg_.n_threads;
    cparams.no_perf = true;

    ctx_ = llama_init_from_model(model_, cparams);
    if (!ctx_) {
        std::cerr << "[MAPLE] Failed to create context." << std::endl;
        return false;
    }
    return true;
}

void LlamaBackend::destroy_context() {
    if (ctx_) {
        llama_free(ctx_);
        ctx_ = nullptr;
    }
}

LlamaBackend::~LlamaBackend() {
    destroy_context();
    if (model_) {
        llama_model_free(model_);
        model_ = nullptr;
    }
}

std::string LlamaBackend::apply_chat_template(const std::string& user_msg) const {
    // Qwen-style non-thinking prompt.  The empty think block is part of the
    // prompt, so generate() returns the final answer directly.
    std::string formatted;
    formatted += "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n";
    formatted += "<|im_start|>user\n" + user_msg + "<|im_end|>\n";
    formatted += "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    return formatted;
}

std::string LlamaBackend::generate(const std::string& prompt) {
    if (!is_loaded()) return "";

    // Recreate context for each generation to avoid KV cache overflow
    llama_memory_clear(llama_get_memory(ctx_), true);

    std::string full_prompt = apply_chat_template(prompt);

    const llama_vocab* vocab = llama_model_get_vocab(model_);

    // Tokenize
    const int n_prompt = -llama_tokenize(
        vocab,
        full_prompt.c_str(),
        static_cast<int32_t>(full_prompt.length()),
        nullptr,
        0,
        true,  // add_special
        true   // parse_special
    );

    if (n_prompt <= 0) {
        std::cerr << "[MAPLE] Tokenization failed." << std::endl;
        return "";
    }

    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(
            vocab,
            full_prompt.c_str(),
            static_cast<int32_t>(full_prompt.length()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true) < 0) {
        std::cerr << "[MAPLE] Tokenization failed (second pass)." << std::endl;
        return "";
    }

    const int n_ctx = llama_n_ctx(ctx_);
    if (n_prompt >= n_ctx - cfg_.max_tokens) {
        std::cerr << "[MAPLE] Prompt too long for context." << std::endl;
        return "";
    }

    // Sampling setup
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* sampler = llama_sampler_chain_init(sparams);

    if (cfg_.temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(cfg_.top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(cfg_.top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(cfg_.temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // Prepare batch for prompt
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    std::string result;
    int n_pos = 0;

    for (int i = 0; i < cfg_.max_tokens; ++i) {
        if (llama_decode(ctx_, batch) != 0) {
            std::cerr << "[MAPLE] Decode failed." << std::endl;
            break;
        }

        n_pos += batch.n_tokens;

        llama_token new_token_id = llama_sampler_sample(sampler, ctx_, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        batch = llama_batch_get_one(&new_token_id, 1);
    }

    llama_sampler_free(sampler);
    return result;
}

} // namespace maple
