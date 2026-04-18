#include "weight_loader.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <cstring>

namespace transformer {

void WeightLoader::load(const std::string& weights_path, const std::string& manifest_path) {
    // Load manifest (simple text format)
    std::ifstream manifest_file(manifest_path);
    if (!manifest_file.is_open()) {
        throw std::runtime_error("Cannot open manifest file: " + manifest_path);
    }
    
    std::string line;
    while (std::getline(manifest_file, line)) {
        line = line.substr(0, line.find('#'));  // Remove comments
        
        // Trim whitespace
        size_t start = line.find_first_not_of(" \t");
        if (start == std::string::npos) continue;
        size_t end = line.find_last_not_of(" \t");
        line = line.substr(start, end - start + 1);
        
        if (line.empty()) continue;
        
        // Parse config lines
        if (line.find('=') != std::string::npos && line.find('|') == std::string::npos) {
            std::istringstream iss(line);
            std::string key_value;
            iss >> key_value;
            
            size_t eq_pos = key_value.find('=');
            if (eq_pos != std::string::npos) {
                std::string key = key_value.substr(0, eq_pos);
                std::string value = key_value.substr(eq_pos + 1);
                
                if (key == "latent_dim") config_.latent_dim = std::stoi(value);
                else if (key == "num_layers") config_.num_layers = std::stoi(value);
                else if (key == "num_heads") config_.num_heads = std::stoi(value);
                else if (key == "ffn_dim") config_.ffn_dim = std::stoi(value);
                else if (key == "rope_base") config_.rope_base = std::stof(value);
                else if (key == "max_apps") config_.max_apps = std::stoi(value);
                else if (key == "max_len") config_.max_len = std::stoi(value);
            }
            continue;
        }
        
        // Parse tensor entry: name|shape|offset|num_elements
        std::istringstream iss(line);
        std::string name, shape_str, offset_str, num_elem_str;
        
        if (!std::getline(iss, name, '|')) continue;
        if (!std::getline(iss, shape_str, '|')) continue;
        if (!std::getline(iss, offset_str, '|')) continue;
        if (!std::getline(iss, num_elem_str, '|')) continue;
        
        // Parse shape
        std::vector<size_t> shape;
        std::istringstream shape_ss(shape_str);
        std::string dim_str;
        while (std::getline(shape_ss, dim_str, ',')) {
            shape.push_back(std::stoull(dim_str));
        }
        
        size_t offset = std::stoull(offset_str);
        size_t num_elements = std::stoull(num_elem_str);
        
        tensor_entries_[name] = {shape, offset, num_elements};
    }
    
    manifest_file.close();
    
    // Load binary weights
    std::ifstream weights_file(weights_path, std::ios::binary);
    if (!weights_file.is_open()) {
        throw std::runtime_error("Cannot open weights file: " + weights_path);
    }
    
    // Read entire file into buffer
    weights_file.seekg(0, std::ios::end);
    size_t file_size = weights_file.tellg();
    weights_file.seekg(0, std::ios::beg);
    
    weights_buffer_.resize(file_size);
    weights_file.read(reinterpret_cast<char*>(weights_buffer_.data()), file_size);
    weights_file.close();
    
}

Tensor WeightLoader::get(const std::string& name) const {
    auto it = tensor_entries_.find(name);
    if (it == tensor_entries_.end()) {
        throw std::runtime_error("Weight not found: " + name);
    }
    
    const auto& entry = it->second;
    std::vector<float> data(entry.num_elements);
    std::memcpy(data.data(), weights_buffer_.data() + entry.offset, entry.num_elements * sizeof(float));
    
    return Tensor(entry.shape, data);
}

bool WeightLoader::has(const std::string& name) const {
    return tensor_entries_.find(name) != tensor_entries_.end();
}

std::vector<std::string> WeightLoader::get_names() const {
    std::vector<std::string> names;
    for (const auto& pair : tensor_entries_) {
        names.push_back(pair.first);
    }
    return names;
}

} // namespace transformer
