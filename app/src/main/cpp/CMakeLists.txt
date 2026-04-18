cmake_minimum_required(VERSION 3.22.1)
project("memo-native")

# OpenMP support for TransformerPredictor (optional; defaults to single-threaded)
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
endif()

add_library(
    memo-native
    SHARED
    bridge/memo_native_bridge.cpp
    predictor/native_score_utils.cpp
    policy/native_policy_utils.cpp
    monitor/native_monitor_hooks.cpp
    util/native_logger.cpp
    TransformerPredictor/src/tensor.cpp
    TransformerPredictor/src/ops.cpp
    TransformerPredictor/src/weight_loader.cpp
    TransformerPredictor/src/model.cpp
    TransformerPredictor/src/inferencer.cpp
    TransformerPredictor/src/transformer_predictor.cpp
)

find_library(log-lib log)

target_include_directories(
    memo-native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/TransformerPredictor/include
)

target_link_libraries(
    memo-native
    ${log-lib}
    OpenMP::OpenMP_CXX
)
