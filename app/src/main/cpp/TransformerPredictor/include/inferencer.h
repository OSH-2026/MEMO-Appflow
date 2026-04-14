#pragma once

#include "model.h"
#include "weight_loader.h"
#include <string>
#include <vector>
#include <tuple>

namespace transformer {

// Main inference class
class TransformerInferencer {
public:
    // Constructor: load model from weight files
    TransformerInferencer(const std::string& weights_path, 
                   const std::string& manifest_path);
    
    // Original single prediction (uses prefill internally)
    // Input: vector of tuples (app_id, action, time_minutes, hour_of_day)
    // Output: probability distribution over apps
    std::vector<float> predict(const std::vector<std::tuple<int, int, float, float>>& sequence);
    
    // Batch prediction
    std::vector<std::vector<float>> predict_batch(
        const std::vector<std::vector<std::tuple<int, int, float, float>>>& sequences);
    
    // === KV Cache API ===
    
    // 1. Prefill: input a sequence, fill KV cache, no probability returned
    // This is used for the initial prompt processing
    void prefill(const std::vector<std::tuple<int, int, float, float>>& sequence);
    
    // 2. Add cache: input a sequence, append to existing cache
    // This is used to extend the context without regenerating probabilities
    void add_cache(const std::vector<std::tuple<int, int, float, float>>& sequence);
    
    // 3. Decode: based on current cache, return prediction for next token
    // Returns probability distribution but does NOT modify the cache
    // The caller decides whether to actually append this token to the sequence
    std::vector<float> decode();
    
    // Utility: clear all KV caches
    void clear_cache();
    
    // Utility: get current cache length
    size_t cache_length() const;
    
    // Get model info
    int vocab_size() const { return model_.vocab_size(); }
    int latent_dim() const { return model_.latent_dim(); }
    int num_layers() const { return model_.num_layers(); }
    
private:
    WeightLoader loader_;
    TransformerModel model_;
    
    // Internal state for KV cache
    std::vector<std::vector<float>> cached_time_minutes_;  // (1, cache_len)
    std::vector<std::vector<float>> cached_hour_of_day_;   // (1, cache_len)
    std::vector<std::vector<int>> cached_app_ids_;         // (1, cache_len)
    std::vector<std::vector<int>> cached_actions_;         // (1, cache_len)
    
    // Convert sequence to model input
    ModelInput prepare_input(const std::vector<std::tuple<int, int, float, float>>& sequence);
    
    // Helper: prepare single token input
    std::tuple<int, int, float, float> get_last_token_info() const;
};

} // namespace transformer
