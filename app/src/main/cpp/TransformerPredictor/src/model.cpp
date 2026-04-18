#include "model.h"
#include <cmath>
#include <iostream>

namespace transformer {

// Helper functions
Tensor load_weight(const WeightLoader& loader, const std::string& name) {
    return loader.get(name);
}

// Embedding layer
void Embedding::load_weights(WeightLoader& loader) {
    app_embedding_weight_ = loader.get("embedding.app_embedding.weight");  // (200, 256)
    action_embedding_weight_ = loader.get("embedding.action_embedding.weight");  // (2, 256)
    
    // Linear weights: PyTorch format (out_features, in_features)
    linear_time_weight_ = loader.get("embedding.linear_time.weight");  // (256, 2)
    linear_time_bias_ = loader.get("embedding.linear_time.bias");  // (256,)
    
    linear_weight_ = loader.get("embedding.linear.weight");  // (256, 768)
    linear_bias_ = loader.get("embedding.linear.bias");  // (256,)
}

Tensor Embedding::forward(const ModelInput& input) {
    size_t B = input.app_ids.size();
    size_t N = input.app_ids[0].size();
    size_t latent_dim = app_embedding_weight_.shape()[1];
    
    // Lookup embeddings
    Tensor app_emb = ops::embedding(input.app_ids, app_embedding_weight_);       // (B, N, latent_dim)
    Tensor action_emb = ops::embedding(input.actions, action_embedding_weight_); // (B, N, latent_dim)
    
    // Compute hour embedding
    std::vector<size_t> hour_shape = {B, N, 2};
    Tensor hour_emb_raw(hour_shape);
    float* hour_ptr = hour_emb_raw.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            float h = input.hour_of_day[b][n] / 24.0f * 2.0f * M_PI;
            hour_ptr[(b * N + n) * 2 + 0] = std::sin(h);
            hour_ptr[(b * N + n) * 2 + 1] = std::cos(h);
        }
    }
    
    // Apply linear_time: hour_emb_raw @ linear_time_weight.T + bias
    Tensor hour_emb = ops::linear(hour_emb_raw, linear_time_weight_, linear_time_bias_);  // (B, N, latent_dim)
    
    // Concatenate: app_emb, action_emb, hour_emb
    std::vector<size_t> concat_shape = {B, N, latent_dim * 3};
    Tensor concat_emb(concat_shape);
    float* concat_ptr = concat_emb.ptr();
    
    const float* app_ptr = app_emb.ptr();
    const float* action_ptr = action_emb.ptr();
    const float* hour_ptr_out = hour_emb.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            float* out = concat_ptr + (b * N + n) * latent_dim * 3;
            for (size_t d = 0; d < latent_dim; ++d) {
                out[d] = app_ptr[(b * N + n) * latent_dim + d];
                out[latent_dim + d] = action_ptr[(b * N + n) * latent_dim + d];
                out[2 * latent_dim + d] = hour_ptr_out[(b * N + n) * latent_dim + d];
            }
        }
    }
    
    // Final projection
    Tensor output = ops::linear(concat_emb, linear_weight_, linear_bias_);  // (B, N, latent_dim)
    
    return output;
}

// Forward for single token (for decode)
Tensor Embedding::forward_single(int app_id, int action, float time_minute, float hour, float total_time) {
    size_t latent_dim = app_embedding_weight_.shape()[1];
    
    // App embedding lookup
    const float* app_emb_table = app_embedding_weight_.ptr();
    const float* app_emb = app_emb_table + app_id * latent_dim;
    
    // Action embedding lookup
    const float* action_emb_table = action_embedding_weight_.ptr();
    const float* action_emb = action_emb_table + action * latent_dim;
    
    // Hour embedding
    float h = hour / 24.0f * 2.0f * M_PI;
    float hour_sin = std::sin(h);
    float hour_cos = std::cos(h);
    
    // hour_emb_raw @ linear_time_weight.T + bias
    Tensor hour_emb_raw({1, 2});
    hour_emb_raw[0] = hour_sin;
    hour_emb_raw[1] = hour_cos;
    Tensor hour_emb = ops::linear(hour_emb_raw, linear_time_weight_, linear_time_bias_);  // (1, latent_dim)
    
    // Concatenate: app_emb, action_emb, hour_emb
    Tensor concat_emb({1, latent_dim * 3});
    float* concat_ptr = concat_emb.ptr();
    const float* hour_ptr = hour_emb.ptr();
    
    for (size_t d = 0; d < latent_dim; ++d) {
        concat_ptr[d] = app_emb[d];
        concat_ptr[latent_dim + d] = action_emb[d];
        concat_ptr[2 * latent_dim + d] = hour_ptr[d];
    }
    
    // Final projection
    Tensor output = ops::linear(concat_emb, linear_weight_, linear_bias_);  // (1, latent_dim)
    
    return output;
}

// Attention block
void AttentionBlock::load_weights(const WeightLoader& loader, int layer_idx) {
    std::string prefix = "blocks." + std::to_string(layer_idx) + ".attn.";
    
    q_weight_ = loader.get(prefix + "Q.weight");  // (256, 256)
    q_bias_ = loader.get(prefix + "Q.bias");      // (256,)
    
    k_weight_ = loader.get(prefix + "K.weight");  // (256, 256)
    k_bias_ = loader.get(prefix + "K.bias");      // (256,)
    
    v_weight_ = loader.get(prefix + "V.weight");  // (256, 256)
    v_bias_ = loader.get(prefix + "V.bias");      // (256,)
    
    w_weight_ = loader.get(prefix + "W.weight");  // (256, 256)
    w_bias_ = loader.get(prefix + "W.bias");      // (256,)
    
    // Load config
    num_heads_ = loader.config().num_heads;
    latent_dim_ = loader.config().latent_dim;
    rope_base_ = loader.config().rope_base;
    
    // Initialize cache (will be resized as needed)
    size_t head_dim = latent_dim_ / num_heads_;
    k_cache_ = Tensor({1, static_cast<size_t>(num_heads_), max_cache_len_, head_dim});
    v_cache_ = Tensor({1, static_cast<size_t>(num_heads_), max_cache_len_, head_dim});
    k_cache_.zeros();
    v_cache_.zeros();
    cache_len_ = 0;
}

void AttentionBlock::clear_cache() {
    cache_len_ = 0;
}

void AttentionBlock::compute_qkv(const Tensor& x, Tensor& q, Tensor& k, Tensor& v) {
    q = ops::linear(x, q_weight_, q_bias_);  // (B, N, latent_dim)
    k = ops::linear(x, k_weight_, k_bias_);
    v = ops::linear(x, v_weight_, v_bias_);
}

void AttentionBlock::apply_rope_with_offset(Tensor& q, Tensor& k, const Tensor& time, size_t position_offset) {
    size_t B = q.shape()[0];
    size_t num_heads = q.shape()[1];
    size_t seq_len = q.shape()[2];
    size_t head_dim = q.shape()[3];
    size_t d_half = head_dim / 2;
    
    float* q_ptr = q.ptr();
    float* k_ptr = k.ptr();
    const float* time_ptr = time.ptr();
    
    // Precompute frequency factors
    // Python: o = 1 / (base ** (2 * o / d))
    std::vector<float> freq(d_half);
    for (size_t i = 0; i < d_half; ++i) {
        freq[i] = 1.0f / std::pow(rope_base_, 2.0f * i / head_dim);
    }
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < seq_len; ++n) {
            // Position includes cache offset for K, absolute for Q
            float t_q = time_ptr[b * seq_len + n];
            float t_k = t_q;  // time already contains absolute position
            
            for (size_t h = 0; h < num_heads; ++h) {
                // Apply to Q
                // Python: rot_half_x = cat((x[..., d//2:], -x[..., :d//2]), dim=-1)
                //         return x * cos + rot_half_x * sin
                // Which means:
                //   out[i] = x[i] * cos + x[i + d/2] * sin  (for i < d/2)
                //   out[i + d/2] = x[i + d/2] * cos - x[i] * sin
                float* q_head = q_ptr + ((b * num_heads + h) * seq_len + n) * head_dim;
                for (size_t d = 0; d < d_half; ++d) {
                    float theta = t_q * freq[d];
                    float cos_theta = std::cos(theta);
                    float sin_theta = std::sin(theta);
                    
                    float x0 = q_head[d];
                    float x1 = q_head[d + d_half];
                    
                    // Match Python: x * cos + rot_half_x * sin
                    q_head[d] = x0 * cos_theta + x1 * sin_theta;
                    q_head[d + d_half] = -x0 * sin_theta + x1 * cos_theta;
                }
                
                // Apply to K
                float* k_head = k_ptr + ((b * num_heads + h) * seq_len + n) * head_dim;
                for (size_t d = 0; d < d_half; ++d) {
                    float theta = t_k * freq[d];
                    float cos_theta = std::cos(theta);
                    float sin_theta = std::sin(theta);
                    
                    float x0 = k_head[d];
                    float x1 = k_head[d + d_half];
                    
                    // Match Python
                    k_head[d] = x0 * cos_theta + x1 * sin_theta;
                    k_head[d + d_half] = -x0 * sin_theta + x1 * cos_theta;
                }
            }
        }
    }
}

void AttentionBlock::append_to_cache(const Tensor& k_new, const Tensor& v_new) {
    size_t B = k_new.shape()[0];
    size_t num_heads = k_new.shape()[1];
    size_t new_len = k_new.shape()[2];
    size_t head_dim = k_new.shape()[3];
    
    if (cache_len_ + new_len > max_cache_len_) {
        throw std::runtime_error("Cache overflow!");
    }
    
    const float* k_ptr = k_new.ptr();
    const float* v_ptr = v_new.ptr();
    float* k_cache_ptr = k_cache_.ptr();
    float* v_cache_ptr = v_cache_.ptr();
    
    // Append to cache
    for (size_t b = 0; b < B; ++b) {
        for (size_t h = 0; h < num_heads; ++h) {
            for (size_t n = 0; n < new_len; ++n) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = ((b * num_heads + h) * new_len + n) * head_dim + d;
                    size_t dst_idx = ((b * num_heads + h) * max_cache_len_ + cache_len_ + n) * head_dim + d;
                    k_cache_ptr[dst_idx] = k_ptr[src_idx];
                    v_cache_ptr[dst_idx] = v_ptr[src_idx];
                }
            }
        }
    }
    
    cache_len_ += new_len;
}

Tensor AttentionBlock::attention_decode(const Tensor& q, const Tensor& k_cache, const Tensor& v_cache, bool use_causal_mask) {
    size_t B = q.shape()[0];
    size_t num_heads = q.shape()[1];
    size_t q_len = q.shape()[2];
    size_t head_dim = q.shape()[3];
    size_t kv_len = cache_len_;
    
    float scale = 1.0f / std::sqrt(static_cast<float>(head_dim));
    
    Tensor output(q.shape());
    output.zeros();
    
    const float* q_ptr = q.ptr();
    const float* k_cache_ptr = k_cache.ptr();
    const float* v_cache_ptr = v_cache.ptr();
    float* out_ptr = output.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t h = 0; h < num_heads; ++h) {
            for (size_t i = 0; i < q_len; ++i) {
                const float* q_vec = q_ptr + ((b * num_heads + h) * q_len + i) * head_dim;
                
                // Determine which keys to attend to
                // For causal mask: query i can only attend to keys 0..(kv_len - q_len + i)
                // This assumes keys and queries are aligned (key j corresponds to query j)
                size_t max_key_idx = kv_len;
                if (use_causal_mask) {
                    // In prefill, keys and queries have the same sequence
                    // query i should only see keys 0..i
                    max_key_idx = std::min(kv_len, i + 1);
                }
                
                // Compute attention scores
                std::vector<float> scores(max_key_idx);
                float max_score = -1e9f;
                
                for (size_t j = 0; j < max_key_idx; ++j) {
                    const float* k_vec = k_cache_ptr + ((b * num_heads + h) * max_cache_len_ + j) * head_dim;
                    float dot = 0.0f;
                    for (size_t d = 0; d < head_dim; ++d) {
                        dot += q_vec[d] * k_vec[d];
                    }
                    scores[j] = dot * scale;
                    if (scores[j] > max_score) {
                        max_score = scores[j];
                    }
                }
                
                // Softmax
                float sum_exp = 0.0f;
                for (size_t j = 0; j < max_key_idx; ++j) {
                    scores[j] = std::exp(scores[j] - max_score);
                    sum_exp += scores[j];
                }
                for (size_t j = 0; j < max_key_idx; ++j) {
                    scores[j] /= sum_exp;
                }
                
                // Weighted sum of values
                float* out_vec = out_ptr + ((b * num_heads + h) * q_len + i) * head_dim;
                for (size_t d = 0; d < head_dim; ++d) {
                    out_vec[d] = 0.0f;
                }
                
                for (size_t j = 0; j < max_key_idx; ++j) {
                    const float* v_vec = v_cache_ptr + ((b * num_heads + h) * max_cache_len_ + j) * head_dim;
                    float weight = scores[j];
                    for (size_t d = 0; d < head_dim; ++d) {
                        out_vec[d] += weight * v_vec[d];
                    }
                }
            }
        }
    }
    
    return output;
}

Tensor AttentionBlock::forward_cache(const Tensor& x, const Tensor& time, int cache_mode) {
    // cache_mode: 0=prefill, 1=add_cache, 2=decode
    
    size_t B = x.shape()[0];
    size_t N = x.shape()[1];
    size_t head_dim = latent_dim_ / num_heads_;
    
    // Compute Q, K, V
    Tensor q, k, v;
    compute_qkv(x, q, k, v);  // (B, N, latent_dim)
    
    // Reshape to (B, num_heads, N, head_dim)
    std::vector<size_t> reshaped_shape = {B, static_cast<size_t>(num_heads_), N, head_dim};
    Tensor q_heads(reshaped_shape);
    Tensor k_heads(reshaped_shape);
    Tensor v_heads(reshaped_shape);
    
    const float* q_ptr = q.ptr();
    const float* k_ptr = k.ptr();
    const float* v_ptr = v.ptr();
    float* qh_ptr = q_heads.ptr();
    float* kh_ptr = k_heads.ptr();
    float* vh_ptr = v_heads.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            for (size_t h = 0; h < static_cast<size_t>(num_heads_); ++h) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = (b * N + n) * latent_dim_ + h * head_dim + d;
                    size_t dst_idx = ((b * num_heads_ + h) * N + n) * head_dim + d;
                    qh_ptr[dst_idx] = q_ptr[src_idx];
                    kh_ptr[dst_idx] = k_ptr[src_idx];
                    vh_ptr[dst_idx] = v_ptr[src_idx];
                }
            }
        }
    }
    
    // Apply RoPE with position offset based on current cache length
    apply_rope_with_offset(q_heads, k_heads, time, cache_len_);
    
    // For prefill (0) and add_cache (1): append new K, V to cache
    // For decode (2): do NOT modify cache, use existing cache only
    if (cache_mode != 2) {
        append_to_cache(k_heads, v_heads);
    }
    
    // Compute attention output
    // For prefill (mode 0): use causal mask (query i only attends to keys 0..i)
    // For add_cache (mode 1) and decode (mode 2): no causal mask (attend to all keys)
    bool use_causal_mask = (cache_mode == 0);
    Tensor attn_out = attention_decode(q_heads, k_cache_, v_cache_, use_causal_mask);
    
    // Reshape back
    Tensor attn_combined({B, N, static_cast<size_t>(latent_dim_)});
    float* ac_ptr = attn_combined.ptr();
    const float* ao_ptr = attn_out.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            for (size_t h = 0; h < static_cast<size_t>(num_heads_); ++h) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = ((b * num_heads_ + h) * N + n) * head_dim + d;
                    size_t dst_idx = (b * N + n) * latent_dim_ + h * head_dim + d;
                    ac_ptr[dst_idx] = ao_ptr[src_idx];
                }
            }
        }
    }
    
    // Output projection
    Tensor output = ops::linear(attn_combined, w_weight_, w_bias_);
    return output;
}

// Original forward (for compatibility)
Tensor AttentionBlock::forward(const Tensor& x, const Tensor& time) {
    // Reset cache for non-cached forward
    clear_cache();
    return forward_cache(x, time, 0);  // prefill mode
}

// Add single token to cache (for correct causal attention)
void AttentionBlock::add_cache_single(const Tensor& x, const Tensor& time) {
    // x: (B=1, N=1, latent_dim) - single token
    // time: (B=1, N=1) - single time value
    
    size_t B = x.shape()[0];
    size_t N = x.shape()[1];  // Should be 1
    size_t head_dim = latent_dim_ / num_heads_;
    
    // Compute Q, K, V for single token
    Tensor q, k, v;
    compute_qkv(x, q, k, v);  // (B, 1, latent_dim)
    
    // Reshape to (B, num_heads, 1, head_dim)
    std::vector<size_t> reshaped_shape = {B, static_cast<size_t>(num_heads_), N, head_dim};
    Tensor q_heads(reshaped_shape);
    Tensor k_heads(reshaped_shape);
    Tensor v_heads(reshaped_shape);
    
    const float* q_ptr = q.ptr();
    const float* k_ptr = k.ptr();
    const float* v_ptr = v.ptr();
    float* qh_ptr = q_heads.ptr();
    float* kh_ptr = k_heads.ptr();
    float* vh_ptr = v_heads.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            for (size_t h = 0; h < static_cast<size_t>(num_heads_); ++h) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = (b * N + n) * latent_dim_ + h * head_dim + d;
                    size_t dst_idx = ((b * num_heads_ + h) * N + n) * head_dim + d;
                    qh_ptr[dst_idx] = q_ptr[src_idx];
                    kh_ptr[dst_idx] = k_ptr[src_idx];
                    vh_ptr[dst_idx] = v_ptr[src_idx];
                }
            }
        }
    }
    
    // Apply RoPE with position offset based on current cache length
    apply_rope_with_offset(q_heads, k_heads, time, cache_len_);
    
    // Append new K, V to cache (only K, V are needed for future decode)
    append_to_cache(k_heads, v_heads);
    
    // Note: We don't compute attention output here because:
    // 1. This is for add_cache only - the output is not used
    // 2. The output would require attention with full cache, which is expensive
    // 3. decode() will compute attention using the updated cache
}

// Decode using existing cache WITHOUT modifying it
Tensor AttentionBlock::decode_with_cache(const Tensor& x, const Tensor& time) {
    // x: (B=1, N=1, latent_dim) - single token
    // time: (B=1, N=1) - single time value
    // Uses existing cache but does NOT append new KV
    
    size_t B = x.shape()[0];
    size_t N = x.shape()[1];  // Should be 1
    size_t head_dim = latent_dim_ / num_heads_;
    
    // Compute Q, K, V for single token
    Tensor q, k, v;
    compute_qkv(x, q, k, v);  // (B, 1, latent_dim)
    
    // Reshape to (B, num_heads, 1, head_dim)
    std::vector<size_t> reshaped_shape = {B, static_cast<size_t>(num_heads_), N, head_dim};
    Tensor q_heads(reshaped_shape);
    Tensor k_heads(reshaped_shape);
    Tensor v_heads(reshaped_shape);
    
    const float* q_ptr = q.ptr();
    const float* k_ptr = k.ptr();
    const float* v_ptr = v.ptr();
    float* qh_ptr = q_heads.ptr();
    float* kh_ptr = k_heads.ptr();
    float* vh_ptr = v_heads.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            for (size_t h = 0; h < static_cast<size_t>(num_heads_); ++h) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = (b * N + n) * latent_dim_ + h * head_dim + d;
                    size_t dst_idx = ((b * num_heads_ + h) * N + n) * head_dim + d;
                    qh_ptr[dst_idx] = q_ptr[src_idx];
                    kh_ptr[dst_idx] = k_ptr[src_idx];
                    vh_ptr[dst_idx] = v_ptr[src_idx];
                }
            }
        }
    }
    
    // Apply RoPE with position offset based on current cache length
    // Position = cache_len_ (next position after cache)
    apply_rope_with_offset(q_heads, k_heads, time, cache_len_);
    
    // Compute attention using EXISTING cache only (do NOT append new KV)
    // For decode, we only use Q from current token with existing K_cache, V_cache
    Tensor attn_out = attention_decode(q_heads, k_cache_, v_cache_);
    
    // Reshape back
    Tensor attn_combined({B, N, static_cast<size_t>(latent_dim_)});
    float* ac_ptr = attn_combined.ptr();
    const float* ao_ptr = attn_out.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            for (size_t h = 0; h < static_cast<size_t>(num_heads_); ++h) {
                for (size_t d = 0; d < head_dim; ++d) {
                    size_t src_idx = ((b * num_heads_ + h) * N + n) * head_dim + d;
                    size_t dst_idx = (b * N + n) * latent_dim_ + h * head_dim + d;
                    ac_ptr[dst_idx] = ao_ptr[src_idx];
                }
            }
        }
    }
    
    // Output projection
    Tensor output = ops::linear(attn_combined, w_weight_, w_bias_);
    return output;
}

// SwishGLU
void SwishGLU::load_weights(const WeightLoader& loader, int layer_idx) {
    std::string prefix = "blocks." + std::to_string(layer_idx) + ".ffn.";
    
    w1_weight_ = loader.get(prefix + "W1.weight");  // (512, 256)
    w1_bias_ = loader.get(prefix + "W1.bias");      // (512,)
    
    w2_weight_ = loader.get(prefix + "W2.weight");  // (256, 512)
    w2_bias_ = loader.get(prefix + "W2.bias");      // (256,)
    
    v_weight_ = loader.get(prefix + "V.weight");    // (512, 256)
    v_bias_ = loader.get(prefix + "V.bias");        // (512,)
}

Tensor SwishGLU::forward(const Tensor& x) {
    Tensor x1 = ops::linear(x, w1_weight_, w1_bias_);
    x1 = ops::silu(x1);
    
    Tensor x2 = ops::linear(x, v_weight_, v_bias_);
    
    Tensor x_mul = ops::mul(x1, x2);
    
    Tensor output = ops::linear(x_mul, w2_weight_, w2_bias_);
    
    return output;
}

// Transformer block
void TransformerBlock::load_weights(const WeightLoader& loader, int layer_idx) {
    attn_.load_weights(loader, layer_idx);
    ffn_.load_weights(loader, layer_idx);
    
    std::string prefix = "blocks." + std::to_string(layer_idx);
    norm1_weight_ = loader.get(prefix + ".norm1.weight");
    norm2_weight_ = loader.get(prefix + ".norm2.weight");
}

Tensor TransformerBlock::forward(const Tensor& x, const Tensor& time) {
    attn_.clear_cache();
    
    Tensor norm1_out = ops::rms_norm(x, norm1_weight_);
    Tensor attn_out = attn_.forward(norm1_out, time);
    Tensor x1 = ops::add(x, attn_out);
    
    Tensor norm2_out = ops::rms_norm(x1, norm2_weight_);
    Tensor ffn_out = ffn_.forward(norm2_out);
    Tensor output = ops::add(x1, ffn_out);
    
    return output;
}

Tensor TransformerBlock::forward_cache(const Tensor& x, const Tensor& time, int cache_mode) {
    Tensor norm1_out = ops::rms_norm(x, norm1_weight_);
    Tensor attn_out = attn_.forward_cache(norm1_out, time, cache_mode);
    Tensor x1 = ops::add(x, attn_out);
    
    Tensor norm2_out = ops::rms_norm(x1, norm2_weight_);
    Tensor ffn_out = ffn_.forward(norm2_out);
    Tensor output = ops::add(x1, ffn_out);
    
    return output;
}

void TransformerBlock::add_cache_single(const Tensor& x, const Tensor& time) {
    // For add_cache, we only need to update KV cache in attention
    // Apply norm, compute KV, append to cache (no output needed)
    Tensor norm1_out = ops::rms_norm(x, norm1_weight_);
    attn_.add_cache_single(norm1_out, time);
    // No FFN computation needed for add_cache - output is discarded
}

Tensor TransformerBlock::decode_with_cache(const Tensor& x, const Tensor& time) {
    // Decode using existing cache WITHOUT modifying it
    // Full forward pass but using decode_with_cache for attention
    
    Tensor norm1_out = ops::rms_norm(x, norm1_weight_);
    Tensor attn_out = attn_.decode_with_cache(norm1_out, time);
    Tensor x1 = ops::add(x, attn_out);
    
    Tensor norm2_out = ops::rms_norm(x1, norm2_weight_);
    Tensor ffn_out = ffn_.forward(norm2_out);
    Tensor output = ops::add(x1, ffn_out);
    
    return output;
}

// Classifier
void Classifier::load_weights(WeightLoader& loader) {
    linear_weight_ = loader.get("classifier.linear.weight");  // (200, 256)
    linear_bias_ = loader.get("classifier.linear.bias");      // (200,)
}

Tensor Classifier::forward(const Tensor& x) {
    return ops::linear(x, linear_weight_, linear_bias_);
}

// Transformer Model
void TransformerModel::load_weights(WeightLoader& loader) {
    latent_dim_ = loader.config().latent_dim;
    num_layers_ = loader.config().num_layers;
    vocab_size_ = loader.config().max_apps;
    
    embedding_.load_weights(loader);
    
    blocks_.resize(num_layers_);
    for (int i = 0; i < num_layers_; ++i) {
        blocks_[i].load_weights(loader, i);
    }
    
    classifier_.load_weights(loader);
    final_norm_weight_ = loader.get("norm.weight");
    
    // Release the raw binary buffer to reduce memory footprint
    loader.release_buffer();
}

Tensor TransformerModel::forward(const ModelInput& input) {
    Tensor x = embedding_.forward(input);
    
    size_t B = input.time_minutes.size();
    size_t N = input.time_minutes[0].size();
    Tensor time({B, N});
    float* time_ptr = time.ptr();
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            time_ptr[b * N + n] = input.time_minutes[b][n];
        }
    }
    
    for (auto& block : blocks_) {
        x = block.forward(x, time);
    }
    
    x = ops::rms_norm(x, final_norm_weight_);
    
    Tensor output = classifier_.forward(x);
    
    return output;
}

// Forward with cache support
Tensor TransformerModel::forward_cache(const Tensor& x, const Tensor& time, int cache_mode) {
    Tensor output = x;
    
    for (auto& block : blocks_) {
        output = block.forward_cache(output, time, cache_mode);
    }
    
    return output;
}

void TransformerModel::add_cache_single(const Tensor& x, const Tensor& time) {
    // Process single token through all layers, updating KV cache at each layer
    // This maintains correct causal attention because each token is processed individually
    //
    // For add_cache:
    // 1. Compute KV for current token and append to cache
    // 2. Compute attention output with updated cache  
    // 3. Compute FFN output for next layer
    
    Tensor current = x;
    
    for (auto& block : blocks_) {
        // Use mode 1 (add_cache) which:
        // - Appends KV to cache
        // - Computes attention with updated cache
        // - Computes FFN output for next layer
        current = block.forward_cache(current, time, 1);
    }
}

Tensor TransformerModel::decode_with_cache(const Tensor& x, const Tensor& time) {
    // Decode using existing cache WITHOUT modifying it
    // This is different from forward_cache mode 2 which appends to cache
    
    Tensor current = x;
    
    for (auto& block : blocks_) {
        current = block.decode_with_cache(current, time);
    }
    
    return current;
}

void TransformerModel::clear_cache() {
    for (auto& block : blocks_) {
        block.clear_cache();
    }
}

size_t TransformerModel::cache_length() const {
    if (blocks_.empty()) {
        return 0;
    }
    return blocks_[0].cache_length();
}

} // namespace transformer
