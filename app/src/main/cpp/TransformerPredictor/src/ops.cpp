#include "ops.h"
#include <numeric>
#include <algorithm>
#include <iostream>

#ifdef _OPENMP
#include <omp.h>
#endif

namespace transformer {
namespace ops {

// Linear layer: y = x @ W^T + b
// Optimized version with continuous memory access
Tensor linear(const Tensor& input, const Tensor& weight, const Tensor& bias) {
    size_t out_features = weight.shape()[0];
    size_t in_features = weight.shape()[1];
    
    std::vector<size_t> out_shape = input.shape();
    out_shape.back() = out_features;
    
    Tensor output(out_shape);
    
    size_t batch_size = 1;
    for (size_t i = 0; i < input.ndim() - 1; ++i) {
        batch_size *= input.shape()[i];
    }
    
    const float* input_ptr = input.ptr();
    const float* weight_ptr = weight.ptr();
    const float* bias_ptr = bias.ptr();
    float* output_ptr = output.ptr();
    
    // Optimized: for each output feature, accumulate from all input features
    #ifdef _OPENMP
    #pragma omp parallel for schedule(static)
    #endif
    for (size_t b = 0; b < batch_size; ++b) {
        const float* x = input_ptr + b * in_features;
        float* y = output_ptr + b * out_features;
        
        for (size_t o = 0; o < out_features; ++o) {
            float sum = bias_ptr[o];
            // Process input features in chunks for better cache utilization
            size_t i = 0;
            // 4-way unroll
            for (; i + 4 <= in_features; i += 4) {
                sum += x[i + 0] * weight_ptr[o * in_features + i + 0];
                sum += x[i + 1] * weight_ptr[o * in_features + i + 1];
                sum += x[i + 2] * weight_ptr[o * in_features + i + 2];
                sum += x[i + 3] * weight_ptr[o * in_features + i + 3];
            }
            // Remainder
            for (; i < in_features; ++i) {
                sum += x[i] * weight_ptr[o * in_features + i];
            }
            y[o] = sum;
        }
    }
    
    return output;
}

// RMSNorm
Tensor rms_norm(const Tensor& input, const Tensor& weight, float eps) {
    if (input.ndim() < 2) {
        throw std::invalid_argument("RMSNorm requires at least 2D input");
    }
    
    size_t B = 1;
    for (size_t i = 0; i < input.ndim() - 1; ++i) {
        B *= input.shape()[i];
    }
    size_t C = input.shape().back();
    
    Tensor output(input.shape());
    
    const float* input_ptr = input.ptr();
    const float* weight_ptr = weight.ptr();
    float* output_ptr = output.ptr();
    
    #ifdef _OPENMP
    #pragma omp parallel for schedule(static)
    #endif
    for (size_t b = 0; b < B; ++b) {
        const float* x = input_ptr + b * C;
        float* y = output_ptr + b * C;
        
        float rms = 0.0f;
        for (size_t c = 0; c < C; ++c) {
            rms += x[c] * x[c];
        }
        rms = std::sqrt(rms / C + eps);
        
        for (size_t c = 0; c < C; ++c) {
            y[c] = (x[c] / rms) * weight_ptr[c];
        }
    }
    
    return output;
}

// Softmax
Tensor softmax(const Tensor& input) {
    std::vector<size_t> shape = input.shape();
    size_t last_dim = shape.back();
    
    size_t batch_size = 1;
    for (size_t i = 0; i < shape.size() - 1; ++i) {
        batch_size *= shape[i];
    }
    
    Tensor output(shape);
    
    const float* input_ptr = input.ptr();
    float* output_ptr = output.ptr();
    
    for (size_t b = 0; b < batch_size; ++b) {
        const float* x = input_ptr + b * last_dim;
        float* y = output_ptr + b * last_dim;
        
        float max_val = x[0];
        for (size_t i = 1; i < last_dim; ++i) {
            if (x[i] > max_val) max_val = x[i];
        }
        
        float sum = 0.0f;
        for (size_t i = 0; i < last_dim; ++i) {
            y[i] = std::exp(x[i] - max_val);
            sum += y[i];
        }
        
        for (size_t i = 0; i < last_dim; ++i) {
            y[i] /= sum;
        }
    }
    
    return output;
}

// SiLU activation
Tensor silu(const Tensor& input) {
    Tensor output(input.shape());
    const float* input_ptr = input.ptr();
    float* output_ptr = output.ptr();
    
    #ifdef _OPENMP
    #pragma omp parallel for schedule(static)
    #endif
    for (size_t i = 0; i < input.size(); ++i) {
        float x = input_ptr[i];
        output_ptr[i] = x / (1.0f + std::exp(-x));
    }
    
    return output;
}

// Element-wise multiplication
Tensor mul(const Tensor& a, const Tensor& b) {
    if (a.shape() != b.shape()) {
        throw std::invalid_argument("Shapes must match for element-wise multiplication");
    }
    
    Tensor output(a.shape());
    const float* a_ptr = a.ptr();
    const float* b_ptr = b.ptr();
    float* out_ptr = output.ptr();
    
    #ifdef _OPENMP
    #pragma omp parallel for schedule(static)
    #endif
    for (size_t i = 0; i < a.size(); ++i) {
        out_ptr[i] = a_ptr[i] * b_ptr[i];
    }
    
    return output;
}

// Element-wise addition
Tensor add(const Tensor& a, const Tensor& b) {
    if (a.shape() != b.shape()) {
        throw std::invalid_argument("Shapes must match for element-wise addition");
    }
    
    Tensor output(a.shape());
    const float* a_ptr = a.ptr();
    const float* b_ptr = b.ptr();
    float* out_ptr = output.ptr();
    
    #ifdef _OPENMP
    #pragma omp parallel for schedule(static)
    #endif
    for (size_t i = 0; i < a.size(); ++i) {
        out_ptr[i] = a_ptr[i] + b_ptr[i];
    }
    
    return output;
}

// Embedding lookup
Tensor embedding(const std::vector<std::vector<int>>& indices, const Tensor& embedding_table) {
    if (indices.empty()) {
        throw std::invalid_argument("Empty indices");
    }
    
    size_t B = indices.size();
    size_t N = indices[0].size();
    size_t embedding_dim = embedding_table.shape()[1];
    
    std::vector<size_t> out_shape = {B, N, embedding_dim};
    Tensor output(out_shape);
    
    const float* table_ptr = embedding_table.ptr();
    float* out_ptr = output.ptr();
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < N; ++n) {
            int idx = indices[b][n];
            if (idx < 0 || static_cast<size_t>(idx) >= embedding_table.shape()[0]) {
                throw std::out_of_range("Embedding index out of range");
            }
            
            const float* emb = table_ptr + idx * embedding_dim;
            float* out = out_ptr + (b * N + n) * embedding_dim;
            
            for (size_t d = 0; d < embedding_dim; ++d) {
                out[d] = emb[d];
            }
        }
    }
    
    return output;
}

// Concatenate along last dimension
Tensor cat(const std::vector<Tensor>& tensors, int dim) {
    if (tensors.empty()) {
        throw std::invalid_argument("Cannot concatenate empty tensor list");
    }
    
    size_t ndim = tensors[0].ndim();
    if (dim < 0) dim = ndim + dim;
    if (dim < 0 || static_cast<size_t>(dim) >= ndim) {
        throw std::out_of_range("Invalid concat dimension");
    }
    
    std::vector<size_t> out_shape = tensors[0].shape();
    size_t concat_dim_size = 0;
    
    for (const auto& t : tensors) {
        if (t.ndim() != ndim) {
            throw std::invalid_argument("All tensors must have same number of dimensions");
        }
        for (size_t i = 0; i < ndim; ++i) {
            if (i != static_cast<size_t>(dim) && t.shape()[i] != out_shape[i]) {
                throw std::invalid_argument("Shapes must match except concat dimension");
            }
        }
        concat_dim_size += t.shape()[dim];
    }
    
    out_shape[dim] = concat_dim_size;
    Tensor output(out_shape);
    
    if (ndim == 2 && (dim == -1 || dim == 1)) {
        size_t rows = out_shape[0];
        float* out_ptr = output.ptr();
        
        for (size_t r = 0; r < rows; ++r) {
            size_t col_offset = 0;
            for (const auto& t : tensors) {
                size_t t_cols = t.shape()[1];
                const float* t_row = t.ptr() + r * t_cols;
                float* out_row = out_ptr + r * out_shape[1] + col_offset;
                std::copy(t_row, t_row + t_cols, out_row);
                col_offset += t_cols;
            }
        }
    } else if (ndim == 1 && dim == 0) {
        float* out_ptr = output.ptr();
        size_t offset = 0;
        for (const auto& t : tensors) {
            const float* t_ptr = t.ptr();
            std::copy(t_ptr, t_ptr + t.size(), out_ptr + offset);
            offset += t.size();
        }
    } else {
        throw std::runtime_error("Concat only supported for 1D or 2D tensors along last dimension");
    }
    
    return output;
}

// RoPE
void apply_rope(Tensor& q, Tensor& k, const Tensor& time, float base) {
    if (q.ndim() != 4 || k.ndim() != 4) {
        throw std::invalid_argument("Q and K must be 4D tensors");
    }
    
    size_t B = q.shape()[0];
    size_t num_heads = q.shape()[1];
    size_t seq_len = q.shape()[2];
    size_t head_dim = q.shape()[3];
    
    if (head_dim % 2 != 0) {
        throw std::invalid_argument("head_dim must be even");
    }
    
    size_t d_half = head_dim / 2;
    
    float* q_ptr = q.ptr();
    float* k_ptr = k.ptr();
    const float* time_ptr = time.ptr();
    
    std::vector<float> freq(d_half);
    for (size_t i = 0; i < d_half; ++i) {
        freq[i] = 1.0f / std::pow(base, 2.0f * i / head_dim);
    }
    
    for (size_t b = 0; b < B; ++b) {
        for (size_t n = 0; n < seq_len; ++n) {
            float t = time_ptr[b * seq_len + n];
            
            for (size_t h = 0; h < num_heads; ++h) {
                float* q_head = q_ptr + ((b * num_heads + h) * seq_len + n) * head_dim;
                for (size_t d = 0; d < d_half; ++d) {
                    float theta = t * freq[d];
                    float cos_theta = std::cos(theta);
                    float sin_theta = std::sin(theta);
                    
                    float x0 = q_head[d];
                    float x1 = q_head[d + d_half];
                    
                    q_head[d] = x0 * cos_theta - x1 * sin_theta;
                    q_head[d + d_half] = x0 * sin_theta + x1 * cos_theta;
                }
                
                float* k_head = k_ptr + ((b * num_heads + h) * seq_len + n) * head_dim;
                for (size_t d = 0; d < d_half; ++d) {
                    float theta = t * freq[d];
                    float cos_theta = std::cos(theta);
                    float sin_theta = std::sin(theta);
                    
                    float x0 = k_head[d];
                    float x1 = k_head[d + d_half];
                    
                    k_head[d] = x0 * cos_theta - x1 * sin_theta;
                    k_head[d + d_half] = x0 * sin_theta + x1 * cos_theta;
                }
            }
        }
    }
}

// Flash Attention with online softmax - memory efficient version
Tensor scaled_dot_product_attention(const Tensor& q, const Tensor& k, const Tensor& v, bool is_causal) {
    size_t B = q.shape()[0];
    size_t num_heads = q.shape()[1];
    size_t seq_len = q.shape()[2];
    size_t head_dim = q.shape()[3];
    
    float scale = 1.0f / std::sqrt(static_cast<float>(head_dim));
    
    Tensor output(q.shape());
    output.zeros();
    
    const float* q_ptr = q.ptr();
    const float* k_ptr = k.ptr();
    const float* v_ptr = v.ptr();
    float* out_ptr = output.ptr();
    
    // Process each batch and head
    for (size_t b = 0; b < B; ++b) {
        for (size_t h = 0; h < num_heads; ++h) {
            // Precompute K transpose for better cache locality
            // K: (seq_len, head_dim) -> K_T: (head_dim, seq_len)
            std::vector<float> k_transposed(head_dim * seq_len);
            for (size_t j = 0; j < seq_len; ++j) {
                const float* k_vec = k_ptr + ((b * num_heads + h) * seq_len + j) * head_dim;
                for (size_t d = 0; d < head_dim; ++d) {
                    k_transposed[d * seq_len + j] = k_vec[d];
                }
            }
            
            // Process each query position
            for (size_t i = 0; i < seq_len; ++i) {
                const float* q_vec = q_ptr + ((b * num_heads + h) * seq_len + i) * head_dim;
                
                // Online softmax
                float max_score = -1e9f;
                float sum_exp = 0.0f;
                size_t j_end = is_causal ? (i + 1) : seq_len;
                
                // First pass: compute scores using transposed K
                std::vector<float> scores(j_end);
                for (size_t j = 0; j < j_end; ++j) {
                    float dot = 0.0f;
                    for (size_t d = 0; d < head_dim; ++d) {
                        dot += q_vec[d] * k_transposed[d * seq_len + j];
                    }
                    scores[j] = dot * scale;
                    if (scores[j] > max_score) {
                        max_score = scores[j];
                    }
                }
                
                // Compute sum of exp
                for (size_t j = 0; j < j_end; ++j) {
                    sum_exp += std::exp(scores[j] - max_score);
                }
                
                // Second pass: accumulate weighted values
                float* out_vec = out_ptr + ((b * num_heads + h) * seq_len + i) * head_dim;
                for (size_t j = 0; j < j_end; ++j) {
                    const float* v_vec = v_ptr + ((b * num_heads + h) * seq_len + j) * head_dim;
                    float attn_weight = std::exp(scores[j] - max_score) / sum_exp;
                    
                    for (size_t d = 0; d < head_dim; ++d) {
                        out_vec[d] += attn_weight * v_vec[d];
                    }
                }
            }
        }
    }
    
    return output;
}

// Matrix multiplication C = A @ B
// Optimized with transposed B for continuous memory access
Tensor matmul(const Tensor& a, const Tensor& b) {
    if (a.ndim() != 2 || b.ndim() != 2) {
        throw std::invalid_argument("matmul requires 2D tensors");
    }
    
    size_t M = a.shape()[0];
    size_t K = a.shape()[1];
    size_t N = b.shape()[1];
    
    if (b.shape()[0] != K) {
        throw std::invalid_argument("Inner dimensions must match for matrix multiplication");
    }
    
    Tensor output({M, N});
    
    const float* a_ptr = a.ptr();
    const float* b_ptr = b.ptr();
    float* out_ptr = output.ptr();
    
    // Transpose B for better cache locality
    // B: (K, N) -> B_T: (N, K)
    std::vector<float> b_transposed(N * K);
    for (size_t k = 0; k < K; ++k) {
        for (size_t n = 0; n < N; ++n) {
            b_transposed[n * K + k] = b_ptr[k * N + n];
        }
    }
    
    // C[m, n] = sum_k A[m, k] * B_T[n, k]
    for (size_t m = 0; m < M; ++m) {
        for (size_t n = 0; n < N; ++n) {
            float sum = 0.0f;
            for (size_t k = 0; k < K; ++k) {
                sum += a_ptr[m * K + k] * b_transposed[n * K + k];
            }
            out_ptr[m * N + n] = sum;
        }
    }
    
    return output;
}

// Batch matrix multiplication
Tensor batch_matmul(const Tensor& a, const Tensor& b) {
    if (a.ndim() != 3 || b.ndim() != 3) {
        throw std::invalid_argument("batch_matmul requires 3D tensors");
    }
    
    size_t B = a.shape()[0];
    size_t M = a.shape()[1];
    size_t K = a.shape()[2];
    size_t N = b.shape()[2];
    
    if (b.shape()[0] != B || b.shape()[1] != K) {
        throw std::invalid_argument("Batch matrix dimensions must match");
    }
    
    Tensor output({B, M, N});
    
    const float* a_ptr = a.ptr();
    const float* b_ptr = b.ptr();
    float* out_ptr = output.ptr();
    
    for (size_t b_idx = 0; b_idx < B; ++b_idx) {
        // Transpose B for this batch
        std::vector<float> b_transposed(N * K);
        for (size_t k = 0; k < K; ++k) {
            for (size_t n = 0; n < N; ++n) {
                b_transposed[n * K + k] = b_ptr[(b_idx * K + k) * N + n];
            }
        }
        
        for (size_t m = 0; m < M; ++m) {
            for (size_t n = 0; n < N; ++n) {
                float sum = 0.0f;
                for (size_t k = 0; k < K; ++k) {
                    sum += a_ptr[(b_idx * M + m) * K + k] * b_transposed[n * K + k];
                }
                out_ptr[(b_idx * M + m) * N + n] = sum;
            }
        }
    }
    
    return output;
}

// Transpose
Tensor transpose(const Tensor& input, int dim0, int dim1) {
    return input.transpose(dim0, dim1);
}

// Create causal mask
Tensor create_causal_mask(size_t seq_len) {
    Tensor mask({seq_len, seq_len});
    float* mask_ptr = mask.ptr();
    
    for (size_t i = 0; i < seq_len; ++i) {
        for (size_t j = 0; j < seq_len; ++j) {
            mask_ptr[i * seq_len + j] = (j <= i) ? 1.0f : 0.0f;
        }
    }
    
    return mask;
}

} // namespace ops
} // namespace transformer
