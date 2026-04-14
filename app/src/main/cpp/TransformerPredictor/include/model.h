#pragma once

#include "tensor.h"
#include "ops.h"
#include "weight_loader.h"
#include <memory>

namespace transformer {

// Input structure for transformer model
struct ModelInput {
    std::vector<std::vector<int>> app_ids;       // (B, N)
    std::vector<std::vector<int>> actions;       // (B, N)
    std::vector<std::vector<float>> time_minutes; // (B, N)
    std::vector<std::vector<float>> hour_of_day; // (B, N)
};

// Embedding layer
class Embedding {
public:
    void load_weights(WeightLoader& loader);
    Tensor forward(const ModelInput& input);
    
    // Forward for single token (for decode)
    Tensor forward_single(int app_id, int action, float time_minute, float hour, float total_time);
    
private:
    Tensor app_embedding_weight_;      // (max_apps, latent_dim)
    Tensor action_embedding_weight_;   // (2, latent_dim)
    Tensor linear_time_weight_;        // (latent_dim, 2) - PyTorch format
    Tensor linear_time_bias_;          // (latent_dim,)
    Tensor linear_weight_;             // (latent_dim, 3*latent_dim) - PyTorch format
    Tensor linear_bias_;               // (latent_dim,)
};

// Attention block with RoPE and KV Cache
class AttentionBlock {
public:
    void load_weights(const WeightLoader& loader, int layer_idx);
    
    // Original forward (for compatibility)
    Tensor forward(const Tensor& x, const Tensor& time);
    
    // Forward with KV cache support
    // cache_mode: 0=prefill (fill cache, no return), 1=add_cache (append to cache), 2=decode (return output for single token)
    Tensor forward_cache(const Tensor& x, const Tensor& time, int cache_mode);
    
    // Add single token to cache (for correct causal attention in add_cache)
    // This processes one token at a time, computing its KV and appending to cache
    void add_cache_single(const Tensor& x, const Tensor& time);
    
    // Decode using existing cache WITHOUT modifying it
    // This computes attention with existing cache but does not append new KV
    Tensor decode_with_cache(const Tensor& x, const Tensor& time);
    
    // Reset/clear cache
    void clear_cache();
    
    // Get current cache length
    size_t cache_length() const { return cache_len_; }
    
    // Helper: compute Q, K, V from input
    void compute_qkv(const Tensor& x, Tensor& q, Tensor& k, Tensor& v);
    
    // Helper: apply RoPE to Q and K with position offset
    void apply_rope_with_offset(Tensor& q, Tensor& k, const Tensor& time, size_t position_offset);
    
private:
    // Weights
    Tensor q_weight_, q_bias_;         // (latent_dim, latent_dim) - PyTorch format
    Tensor k_weight_, k_bias_;         // (latent_dim, latent_dim) - PyTorch format
    Tensor v_weight_, v_bias_;         // (latent_dim, latent_dim) - PyTorch format
    Tensor w_weight_, w_bias_;         // (latent_dim, latent_dim) - PyTorch format
    
    // Config
    int num_heads_ = 4;
    int latent_dim_ = 256;
    float rope_base_ = 100000.0f;
    
    // KV Cache (B=1, num_heads, cache_len, head_dim)
    Tensor k_cache_;  // Key cache
    Tensor v_cache_;  // Value cache
    size_t cache_len_ = 0;
    size_t max_cache_len_ = 4096;
    
    // Helper: attention with optional causal mask
    // For prefill: use causal mask (query i only attends to keys 0..i)
    // For add_cache/decode: no causal mask (query attends to all keys)
    Tensor attention_decode(const Tensor& q, const Tensor& k_cache, const Tensor& v_cache, bool use_causal_mask = false);
    
    // Helper: append new k,v to cache
    void append_to_cache(const Tensor& k_new, const Tensor& v_new);
};

// SwishGLU feed-forward network
class SwishGLU {
public:
    void load_weights(const WeightLoader& loader, int layer_idx);
    Tensor forward(const Tensor& x);
    
private:
    Tensor w1_weight_, w1_bias_;       // (ffn_dim, latent_dim) - PyTorch format
    Tensor w2_weight_, w2_bias_;       // (latent_dim, ffn_dim) - PyTorch format
    Tensor v_weight_, v_bias_;         // (ffn_dim, latent_dim) - PyTorch format
};

// Transformer block (Attention + SwishGLU)
class TransformerBlock {
public:
    void load_weights(const WeightLoader& loader, int layer_idx);
    Tensor forward(const Tensor& x, const Tensor& time);
    
    // Forward with KV cache support
    // cache_mode: 0=prefill, 1=add_cache, 2=decode
    Tensor forward_cache(const Tensor& x, const Tensor& time, int cache_mode);
    
    // Add single token to cache (for correct causal attention)
    void add_cache_single(const Tensor& x, const Tensor& time);
    
    // Decode using existing cache (read-only, does not modify cache)
    Tensor decode_with_cache(const Tensor& x, const Tensor& time);
    
    // Clear cache in attention
    void clear_cache() { attn_.clear_cache(); }
    
    // Get cache length
    size_t cache_length() const { return attn_.cache_length(); }
    
    // Accessors for TransformerModel::add_cache_single and testing
    AttentionBlock& attn() { return attn_; }
    SwishGLU& ffn() { return ffn_; }
    const Tensor& get_norm1_weight() const { return norm1_weight_; }
    const Tensor& get_norm2_weight() const { return norm2_weight_; }
    
private:
    AttentionBlock attn_;
    SwishGLU ffn_;
    Tensor norm1_weight_;              // (latent_dim,)
    Tensor norm2_weight_;              // (latent_dim,)
};

// Output classifier
class Classifier {
public:
    void load_weights(WeightLoader& loader);
    Tensor forward(const Tensor& x);
    
private:
    Tensor linear_weight_;             // (vocab_size, latent_dim) - PyTorch format
    Tensor linear_bias_;               // (vocab_size,)
};

// Main transformer model
class TransformerModel {
public:
    void load_weights(WeightLoader& loader);
    Tensor forward(const ModelInput& input);
    
    // Get model config
    int latent_dim() const { return latent_dim_; }
    int num_layers() const { return num_layers_; }
    int vocab_size() const { return vocab_size_; }
    
    // KV Cache operations
    // Forward with cache: cache_mode 0=prefill, 1=add_cache, 2=decode
    Tensor forward_cache(const Tensor& x, const Tensor& time, int cache_mode);
    
    // Add single token to cache (for correct causal attention in add_cache)
    void add_cache_single(const Tensor& x, const Tensor& time);
    
    // Decode using existing cache WITHOUT modifying it
    Tensor decode_with_cache(const Tensor& x, const Tensor& time);
    
    // Clear all KV caches
    void clear_cache();
    
    // Get cache length (assuming all layers have same cache length)
    size_t cache_length() const;
    
    // Access to components (for inferencer)
    Embedding& embedding() { return embedding_; }
    Classifier& classifier() { return classifier_; }
    Tensor& final_norm_weight() { return final_norm_weight_; }
    std::vector<TransformerBlock>& blocks() { return blocks_; }
    
private:
    Embedding embedding_;
    std::vector<TransformerBlock> blocks_;
    Classifier classifier_;
    Tensor final_norm_weight_;         // (latent_dim,)
    
    int latent_dim_ = 256;
    int num_layers_ = 8;
    int vocab_size_ = 200;
};

} // namespace transformer
