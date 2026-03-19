cmake_minimum_required(VERSION 3.22.1)
project("memo-native")

add_library(
    memo-native
    SHARED
    bridge/memo_native_bridge.cpp
    predictor/native_score_utils.cpp
    policy/native_policy_utils.cpp
    monitor/native_monitor_hooks.cpp
    util/native_logger.cpp
)

find_library(log-lib log)

target_include_directories(
    memo-native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    memo-native
    ${log-lib}
)
