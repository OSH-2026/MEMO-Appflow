#include "tensor.h"
#include <cassert>

namespace transformer {

// Private helper functions
size_t Tensor::compute_size(const std::vector<size_t>& shape) {
    if (shape.empty()) return 0;
    return std::accumulate(shape.begin(), shape.end(), 1ULL, std::multiplies<size_t>());
}

size_t Tensor::compute_index(const std::vector<size_t>& indices) const {
    if (indices.size() != shape_.size()) {
        throw std::invalid_argument("Number of indices must match tensor dimensions");
    }
    
    size_t index = 0;
    auto strides = this->strides();
    for (size_t i = 0; i < indices.size(); ++i) {
        if (indices[i] >= shape_[i]) {
            throw std::out_of_range("Index out of bounds");
        }
        index += indices[i] * strides[i];
    }
    return index;
}

// Constructors
Tensor::Tensor(const std::vector<size_t>& shape) 
    : shape_(shape), data_(compute_size(shape), 0.0f) {}

Tensor::Tensor(const std::vector<size_t>& shape, float value) 
    : shape_(shape), data_(compute_size(shape), value) {}

Tensor::Tensor(const std::vector<size_t>& shape, const std::vector<float>& data) 
    : shape_(shape) {
    size_t expected_size = compute_size(shape);
    if (data.size() != expected_size) {
        throw std::invalid_argument("Data size does not match shape");
    }
    data_ = data;
}

// Element access
float& Tensor::at(const std::vector<size_t>& indices) {
    return data_[compute_index(indices)];
}

const float& Tensor::at(const std::vector<size_t>& indices) const {
    return data_[compute_index(indices)];
}

// Stride calculation
std::vector<size_t> Tensor::strides() const {
    if (shape_.empty()) return {};
    
    std::vector<size_t> strides(shape_.size());
    strides.back() = 1;
    for (int i = static_cast<int>(shape_.size()) - 2; i >= 0; --i) {
        strides[i] = strides[i + 1] * shape_[i + 1];
    }
    return strides;
}

// Shape manipulation
Tensor Tensor::reshape(const std::vector<size_t>& new_shape) const {
    size_t new_size = compute_size(new_shape);
    if (new_size != data_.size()) {
        throw std::invalid_argument("New shape size does not match tensor size");
    }
    return Tensor(new_shape, data_);
}

Tensor Tensor::transpose(size_t dim0, size_t dim1) const {
    if (dim0 >= shape_.size() || dim1 >= shape_.size()) {
        throw std::out_of_range("Dimension index out of range");
    }
    
    std::vector<size_t> new_shape = shape_;
    std::swap(new_shape[dim0], new_shape[dim1]);
    
    Tensor result(new_shape);
    
    // This is a simplified transpose that works for 2D tensors
    // For higher dimensions, we'd need a more general approach
    if (shape_.size() == 2) {
        size_t rows = shape_[0];
        size_t cols = shape_[1];
        for (size_t i = 0; i < rows; ++i) {
            for (size_t j = 0; j < cols; ++j) {
                result.at({j, i}) = this->at({i, j});
            }
        }
    } else {
        // For now, just copy data for unsupported cases
        // A full implementation would need to handle arbitrary dimensions
        result.data_ = data_;
    }
    
    return result;
}

// In-place operations
void Tensor::fill(float value) {
    std::fill(data_.begin(), data_.end(), value);
}

// Arithmetic operations
Tensor Tensor::operator+(const Tensor& other) const {
    if (!shapes_equal(*this, other)) {
        throw std::invalid_argument("Tensor shapes must match for addition");
    }
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] + other[i];
    }
    return result;
}

Tensor Tensor::operator-(const Tensor& other) const {
    if (!shapes_equal(*this, other)) {
        throw std::invalid_argument("Tensor shapes must match for subtraction");
    }
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] - other[i];
    }
    return result;
}

Tensor Tensor::operator*(float scalar) const {
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] * scalar;
    }
    return result;
}

Tensor Tensor::operator/(float scalar) const {
    if (scalar == 0.0f) {
        throw std::runtime_error("Division by zero");
    }
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] / scalar;
    }
    return result;
}

Tensor& Tensor::operator+=(const Tensor& other) {
    if (!shapes_equal(*this, other)) {
        throw std::invalid_argument("Tensor shapes must match for addition");
    }
    for (size_t i = 0; i < data_.size(); ++i) {
        data_[i] += other[i];
    }
    return *this;
}

Tensor& Tensor::operator-=(const Tensor& other) {
    if (!shapes_equal(*this, other)) {
        throw std::invalid_argument("Tensor shapes must match for subtraction");
    }
    for (size_t i = 0; i < data_.size(); ++i) {
        data_[i] -= other[i];
    }
    return *this;
}

Tensor& Tensor::operator*=(float scalar) {
    for (size_t i = 0; i < data_.size(); ++i) {
        data_[i] *= scalar;
    }
    return *this;
}

Tensor& Tensor::operator/=(float scalar) {
    if (scalar == 0.0f) {
        throw std::runtime_error("Division by zero");
    }
    for (size_t i = 0; i < data_.size(); ++i) {
        data_[i] /= scalar;
    }
    return *this;
}

Tensor Tensor::operator+(float scalar) const {
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] + scalar;
    }
    return result;
}

Tensor Tensor::operator-(float scalar) const {
    Tensor result(shape_);
    for (size_t i = 0; i < data_.size(); ++i) {
        result[i] = data_[i] - scalar;
    }
    return result;
}

// Print info
void Tensor::print_shape(const std::string& name) const {
    if (!name.empty()) {
        std::cout << name << ": ";
    }
    std::cout << "Tensor(";
    for (size_t i = 0; i < shape_.size(); ++i) {
        std::cout << shape_[i];
        if (i < shape_.size() - 1) std::cout << ", ";
    }
    std::cout << ")" << std::endl;
}

// Utility functions
bool shapes_equal(const Tensor& a, const Tensor& b) {
    return a.shape() == b.shape();
}

Tensor zeros(const std::vector<size_t>& shape) {
    return Tensor(shape, 0.0f);
}

Tensor ones(const std::vector<size_t>& shape) {
    return Tensor(shape, 1.0f);
}

Tensor empty(const std::vector<size_t>& shape) {
    return Tensor(shape);
}

} // namespace transformer
