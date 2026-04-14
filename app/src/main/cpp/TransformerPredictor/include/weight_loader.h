#pragma once

#include "tensor.h"
#include <string>
#include <unordered_map>
#include <vector>

namespace transformer {

// Weight loader for transformer model
class WeightLoader {
public:
    struct TensorEntry {
        std::vector<size_t> shape;
        size_t offset;
        size_t num_elements;
    };
    
    struct Config {
        int latent_dim = 256;
        int num_layers = 8;
        int num_heads = 4;
        int ffn_dim = 512;
        float rope_base = 100000.0f;
        int max_apps = 200;
        int max_len = 2048;
    };
    
    WeightLoader() = default;
    
    // Load weights from binary file and manifest
    void load(const std::string& weights_path, const std::string& manifest_path);
    
    // Get a specific weight tensor
    Tensor get(const std::string& name) const;
    
    // Check if weight exists
    bool has(const std::string& name) const;
    
    // Get all weight names
    std::vector<std::string> get_names() const;
    
    // Get model config
    const Config& config() const { return config_; }
    
    // Release the raw weights buffer to save memory after all tensors are loaded
    void release_buffer() {
        weights_buffer_.clear();
        weights_buffer_.shrink_to_fit();
    }
    
private:
    std::unordered_map<std::string, TensorEntry> tensor_entries_;
    std::vector<uint8_t> weights_buffer_;
    Config config_;
};

} // namespace transformer
