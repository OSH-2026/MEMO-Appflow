cmake_minimum_required(VERSION 3.22.1)
project(memo_maple_jni LANGUAGES CXX)

add_library(maple-jni SHARED maple_jni.cpp)
target_compile_features(maple-jni PRIVATE cxx_std_17)
target_link_libraries(maple-jni PRIVATE dl log)
