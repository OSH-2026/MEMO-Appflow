#pragma once

#include <vector>
#include <cstddef>
#include <stdexcept>
#include <string>
#include <cmath>
#include <iostream>
#include <numeric>
#include <algorithm>

namespace transformer {

// Simple tensor class for transformer inference
class Tensor {
public:
    // Constructors
    Tensor() = default;
    explicit Tensor(const std::vector<size_t>& shape);
    Tensor(const std::vector<size_t>& shape, float value);
    Tensor(const std::vector<size_t>& shape, const std::vector<float>& data);
    
    // Copy/Move constructors and assignments
    Tensor(const Tensor& other) = default;
    Tensor(Tensor&& other) = default;
    Tensor& operator=(const Tensor& other) = default;
    Tensor& operator=(Tensor&& other) = default;
    
    // Shape information
    const std::vector<size_t>& shape() const { return shape_; }
    size_t ndim() const { return shape_.size(); }
    size_t size() const { return data_.size(); }
    size_t dim(size_t i) const { 
        if (i >= shape_.size()) throw std::out_of_range("Dimension index out of range");
        return shape_[i]; 
    }
    
    // Data access
    const std::vector<float>& data() const { return data_; }
    std::vector<float>& data() { return data_; }
    const float* ptr() const { return data_.data(); }
    float* ptr() { return data_.data(); }
    
    // Element access (linear index)
    float& operator[](size_t idx) { return data_[idx]; }
    const float& operator[](size_t idx) const { return data_[idx]; }
    
    // Element access (multi-dimensional index)
    float& at(const std::vector<size_t>& indices);
    const float& at(const std::vector<size_t>& indices) const;
    
    // Stride calculation
    std::vector<size_t> strides() const;
    
    // Shape manipulation (returns new tensor)
    Tensor reshape(const std::vector<size_t>& new_shape) const;
    Tensor transpose(size_t dim0, size_t dim1) const;
    
    // In-place operations
    void fill(float value);
    void zeros() { fill(0.0f); }
    void ones() { fill(1.0f); }
    
    // Arithmetic operations
    Tensor operator+(const Tensor& other) const;
    Tensor operator-(const Tensor& other) const;
    Tensor operator*(float scalar) const;
    Tensor operator/(float scalar) const;
    
    // In-place arithmetic
    Tensor& operator+=(const Tensor& other);
    Tensor& operator-=(const Tensor& other);
    Tensor& operator*=(float scalar);
    Tensor& operator/=(float scalar);
    
    // Element-wise operations
    Tensor operator+(float scalar) const;
    Tensor operator-(float scalar) const;
    
    // Print info
    void print_shape(const std::string& name = "") const;
    
private:
    std::vector<size_t> shape_;
    std::vector<float> data_;
    
    size_t compute_index(const std::vector<size_t>& indices) const;
    static size_t compute_size(const std::vector<size_t>& shape);
};

// Utility functions
bool shapes_equal(const Tensor& a, const Tensor& b);
Tensor zeros(const std::vector<size_t>& shape);
Tensor ones(const std::vector<size_t>& shape);
Tensor empty(const std::vector<size_t>& shape);

} // namespace transformer
