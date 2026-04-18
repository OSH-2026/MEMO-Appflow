#pragma once

#include "tensor.h"
#include <cmath>

namespace transformer {
namespace ops {

// Linear layer: y = x @ W^T + b
// x: (B, N, in_features) or (N, in_features)
// weight: (out_features, in_features)
// bias: (out_features,)
// output: (B, N, out_features) or (N, out_features)
Tensor linear(const Tensor& input, const Tensor& weight, const Tensor& bias);

// RMSNorm: x / sqrt(mean(x^2) + eps)
// Input: (B, N, C)
// weight: (C,)
// Output: (B, N, C)
Tensor rms_norm(const Tensor& input, const Tensor& weight, float eps = 1.1920929e-7f);

// Softmax along last dimension
// Input: (..., num_classes)
// Output: (..., num_classes)
Tensor softmax(const Tensor& input);

// SiLU (Swish) activation: x * sigmoid(x)
// Input: any shape
// Output: same shape
Tensor silu(const Tensor& input);

// Element-wise multiplication
Tensor mul(const Tensor& a, const Tensor& b);

// Element-wise addition
Tensor add(const Tensor& a, const Tensor& b);

// Embedding lookup
// indices: (B, N) int indices
// embedding_table: (num_embeddings, embedding_dim)
// Output: (B, N, embedding_dim)
Tensor embedding(const std::vector<std::vector<int>>& indices, const Tensor& embedding_table);

// Concatenate along last dimension
// tensors: list of tensors with same shape except last dimension
// Output: concatenated tensor
Tensor cat(const std::vector<Tensor>& tensors, int dim = -1);

// RoPE (Rotary Position Embedding)
// Apply rotary embeddings to Q and K tensors
// q, k: (B, num_heads, seq_len, head_dim)
// time: (B, seq_len) - relative time in minutes
// base: float - RoPE base frequency
void apply_rope(Tensor& q, Tensor& k, const Tensor& time, float base);

// Scaled dot-product attention with causal mask
// q, k, v: (B, num_heads, seq_len, head_dim)
// Output: (B, num_heads, seq_len, head_dim)
Tensor scaled_dot_product_attention(const Tensor& q, const Tensor& k, const Tensor& v, bool is_causal = true);

// Matrix multiplication: C = A @ B
// A: (M, K)
// B: (K, N)
// Output: (M, N)
Tensor matmul(const Tensor& a, const Tensor& b);

// Batch matrix multiplication: C = A @ B
// A: (B, M, K)
// B: (B, K, N)
// Output: (B, M, N)
Tensor batch_matmul(const Tensor& a, const Tensor& b);

// Transpose tensor
// Input: (..., dim0, ..., dim1, ...)
// Output: (..., dim1, ..., dim0, ...)
Tensor transpose(const Tensor& input, int dim0, int dim1);

// Create causal attention mask
// seq_len: sequence length
// Output: (seq_len, seq_len) bool mask where mask[i,j] = (j <= i)
Tensor create_causal_mask(size_t seq_len);

} // namespace ops
} // namespace transformer
