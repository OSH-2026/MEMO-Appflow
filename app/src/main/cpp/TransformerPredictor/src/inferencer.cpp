#include "inferencer.h"
#include <stdexcept>
#include <algorithm>
#include <cmath>

namespace transformer {

TransformerInferencer::TransformerInferencer(const std::string& weights_path, 
                               const std::string& manifest_path) {
    // Load weights
    loader_.load(weights_path, manifest_path);
    
    // Initialize model
    model_.load_weights(loader_);
}

ModelInput TransformerInferencer::prepare_input(
    const std::vector<std::tuple<int, int, float, float>>& sequence) {
    
    ModelInput input;
    
    // Convert single sequence to batch of 1
    size_t seq_len = sequence.size();
    
    input.app_ids.resize(1, std::vector<int>(seq_len));
    input.actions.resize(1, std::vector<int>(seq_len));
    input.time_minutes.resize(1, std::vector<float>(seq_len));
    input.hour_of_day.resize(1, std::vector<float>(seq_len));
    
    for (size_t i = 0; i < seq_len; ++i) {
        input.app_ids[0][i] = std::get<0>(sequence[i]);
        input.actions[0][i] = std::get<1>(sequence[i]);
        input.time_minutes[0][i] = std::get<2>(sequence[i]);
        input.hour_of_day[0][i] = std::get<3>(sequence[i]);
        
        // Validate inputs
        if (input.app_ids[0][i] < 0 || input.app_ids[0][i] >= model_.vocab_size()) {
            throw std::out_of_range("app_id out of range");
        }
        if (input.actions[0][i] < 0 || input.actions[0][i] > 1) {
            throw std::out_of_range("action must be 0 or 1");
        }
        if (input.hour_of_day[0][i] < 0 || input.hour_of_day[0][i] >= 24) {
            throw std::out_of_range("hour_of_day must be in [0, 24)");
        }
    }
    
    return input;
}

std::vector<float> TransformerInferencer::predict(
    const std::vector<std::tuple<int, int, float, float>>& sequence) {
    
    if (sequence.empty()) {
        throw std::invalid_argument("Empty input sequence");
    }
    
    // Use prefill + decode internally
    prefill(sequence);
    return decode();
}

std::vector<std::vector<float>> TransformerInferencer::predict_batch(
    const std::vector<std::vector<std::tuple<int, int, float, float>>>& sequences) {
    
    if (sequences.empty()) {
        return {};
    }
    
    // For simplicity, process each sequence individually
    std::vector<std::vector<float>> results;
    results.reserve(sequences.size());
    
    for (const auto& seq : sequences) {
        results.push_back(predict(seq));
    }
    
    return results;
}

// === KV Cache API Implementation ===

void TransformerInferencer::prefill(const std::vector<std::tuple<int, int, float, float>>& sequence) {
    if (sequence.empty()) {
        throw std::invalid_argument("Empty input sequence for prefill");
    }
    
    // Clear existing cache
    clear_cache();
    
    // Store full token information for later use
    size_t seq_len = sequence.size();
    cached_time_minutes_.resize(1);
    cached_hour_of_day_.resize(1);
    cached_app_ids_.resize(1);
    cached_actions_.resize(1);
    cached_time_minutes_[0].reserve(seq_len);
    cached_hour_of_day_[0].reserve(seq_len);
    cached_app_ids_[0].reserve(seq_len);
    cached_actions_[0].reserve(seq_len);
    
    for (size_t i = 0; i < seq_len; ++i) {
        cached_app_ids_[0].push_back(std::get<0>(sequence[i]));
        cached_actions_[0].push_back(std::get<1>(sequence[i]));
        cached_time_minutes_[0].push_back(std::get<2>(sequence[i]));
        cached_hour_of_day_[0].push_back(std::get<3>(sequence[i]));
    }
    
    // Prepare input for embedding
    ModelInput input = prepare_input(sequence);
    
    // Run embedding
    Tensor x = model_.embedding().forward(input);
    
    // Create time tensor
    size_t B = 1;
    Tensor time({B, seq_len});
    float* time_ptr = time.ptr();
    for (size_t n = 0; n < seq_len; ++n) {
        time_ptr[n] = cached_time_minutes_[0][n];
    }
    
    // Run through transformer blocks with cache_mode=0 (prefill)
    x = model_.forward_cache(x, time, 0);
    
    // Note: For prefill mode, we don't return anything
    // The cache has been filled in the attention blocks
}

void TransformerInferencer::add_cache(const std::vector<std::tuple<int, int, float, float>>& sequence) {
    if (sequence.empty()) {
        return;  // Nothing to add
    }
    
    if (cached_time_minutes_.empty() || cached_time_minutes_[0].empty()) {
        // No existing cache, treat as prefill
        prefill(sequence);
        return;
    }
    
    // Append full token information
    size_t original_len = cached_time_minutes_[0].size();
    size_t seq_len = sequence.size();
    
    for (size_t i = 0; i < seq_len; ++i) {
        cached_app_ids_[0].push_back(std::get<0>(sequence[i]));
        cached_actions_[0].push_back(std::get<1>(sequence[i]));
        cached_time_minutes_[0].push_back(std::get<2>(sequence[i]));
        cached_hour_of_day_[0].push_back(std::get<3>(sequence[i]));
    }
    
    // Prepare embeddings for all tokens at once (more efficient)
    ModelInput input = prepare_input(sequence);
    Tensor x = model_.embedding().forward(input);  // (1, seq_len, latent_dim)
    
    // Process each token individually for correct causal attention
    size_t latent_dim = model_.latent_dim();
    for (size_t i = 0; i < seq_len; ++i) {
        // Extract single token embedding
        Tensor single_x({1, 1, latent_dim});
        float* dst = single_x.ptr();
        const float* src = x.ptr() + i * latent_dim;
        for (size_t d = 0; d < latent_dim; ++d) {
            dst[d] = src[d];
        }
        
        // Create time tensor for this token
        Tensor time({1, 1});
        time[0] = cached_time_minutes_[0][original_len + i];
        
        // Process through all layers, updating KV cache
        model_.add_cache_single(single_x, time);
    }
}

std::vector<float> TransformerInferencer::decode() {
    if (cached_time_minutes_.empty() || cached_time_minutes_[0].empty()) {
        throw std::runtime_error("No cache available for decode. Call prefill first.");
    }
    
    size_t cache_len = cached_time_minutes_[0].size();
    
    // Use the actual last token for embedding input (to match Python behavior)
    int last_app_id = cached_app_ids_[0].back();
    int last_action = cached_actions_[0].back();
    float last_time = cached_time_minutes_[0].back();
    float last_hour = cached_hour_of_day_[0].back();
    
    // Create embedding for single token
    Tensor x = model_.embedding().forward_single(last_app_id, last_action, 
                                                  last_time, last_hour, 
                                                  last_time);
    
    // Reshape to (1, 1, latent_dim)
    x = x.reshape({1, 1, static_cast<size_t>(model_.latent_dim())});
    
    // Create time tensor for this position
    Tensor time({1, 1});
    time[0] = last_time;
    
    // Run through transformer blocks using existing cache (read-only, does not modify cache)
    x = model_.decode_with_cache(x, time);
    
    // Apply final norm
    x = ops::rms_norm(x, model_.final_norm_weight());
    
    // Classifier
    Tensor logits = model_.classifier().forward(x);
    
    // Softmax
    Tensor probs = ops::softmax(logits);
    
    // Convert to vector
    size_t vocab_size = model_.vocab_size();
    std::vector<float> result(vocab_size);
    const float* probs_ptr = probs.ptr();
    for (size_t i = 0; i < vocab_size; ++i) {
        result[i] = probs_ptr[i];
    }
    
    return result;
}

void TransformerInferencer::clear_cache() {
    cached_time_minutes_.clear();
    cached_hour_of_day_.clear();
    cached_app_ids_.clear();
    cached_actions_.clear();
    model_.clear_cache();
}

size_t TransformerInferencer::cache_length() const {
    if (cached_time_minutes_.empty()) {
        return 0;
    }
    return cached_time_minutes_[0].size();
}

} // namespace transformer
