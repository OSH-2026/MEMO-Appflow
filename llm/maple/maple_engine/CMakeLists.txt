cmake_minimum_required(VERSION 3.16)
project(maple_engine LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp paths
# set(LLAMA_CPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp)
# set(LLAMA_BUILD ${LLAMA_CPP_ROOT}/build-andorid CACHE PATH "Path to the llama.cpp build directory")
set(LLAMA_CPP_ROOT /home/javaherobrine/桌面/osh-2026/MEMO-OS/llm/maple/llama.cpp)
set(LLAMA_BUILD ${LLAMA_CPP_ROOT}/build-andorid CACHE PATH "Path to the llama.cpp build directory")



# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${LLAMA_CPP_ROOT}/include
    ${LLAMA_CPP_ROOT}/ggml/include
)

# Source files
set(SOURCES
    src/llama_backend.cpp
    src/prompt_builder.cpp
    src/result_parser.cpp
    src/maple_engine.cpp
)

# Static libraries from llama.cpp build
set(LLAMA_LIBS
    /home/javaherobrine/桌面/osh-2026/MEMO-OS/llm/maple/llama.cpp/build-android/src/libllama.a
    /home/javaherobrine/桌面/osh-2026/MEMO-OS/llm/maple/llama.cpp/build-android/ggml/src/libggml.a
    /home/javaherobrine/桌面/osh-2026/MEMO-OS/llm/maple/llama.cpp/build-android/ggml/src/libggml-base.a
    /home/javaherobrine/桌面/osh-2026/MEMO-OS/llm/maple/llama.cpp/build-android/ggml/src/libggml-cpu.a
)
if(EXISTS ${LLAMA_BUILD}/ggml/src/ggml-cuda/libggml-cuda.a)
    list(APPEND LLAMA_LIBS ${LLAMA_BUILD}/ggml/src/ggml-cuda/libggml-cuda.a)
endif()
if(EXISTS ${LLAMA_BUILD}/common/libllama-common.a)
    list(APPEND LLAMA_LIBS ${LLAMA_BUILD}/common/libllama-common.a)
endif()

# Check if all llama libs exist
# foreach(lib ${LLAMA_LIBS})
#    if(NOT EXISTS ${lib})
#        message(FATAL_ERROR "Missing llama.cpp library: ${lib}. Please build llama.cpp first.")
#    endif()
# endforeach()

# Main shared library (for glue code: Python ctypes / JNI)
add_library(maple_engine SHARED ${SOURCES})
target_link_libraries(maple_engine PRIVATE ${LLAMA_LIBS})

# Also build a static library
add_library(maple_engine_static STATIC ${SOURCES})
target_link_libraries(maple_engine_static PRIVATE ${LLAMA_LIBS})
set_target_properties(maple_engine_static PROPERTIES OUTPUT_NAME maple_engine)

# Platform-specific link libraries
if(WIN32)
    # Windows
elseif(APPLE)
    target_link_libraries(maple_engine PRIVATE "-framework Accelerate" "-framework Foundation" "-framework Metal" "-framework MetalKit")
    target_link_libraries(maple_engine_static PRIVATE "-framework Accelerate" "-framework Foundation" "-framework Metal" "-framework MetalKit")
elseif(ANDROID)
    find_package(Threads REQUIRED)
    target_link_libraries(maple_engine PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
    target_link_libraries(maple_engine_static PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
else()
    # Linux
    find_package(Threads REQUIRED)
    target_link_libraries(maple_engine PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
    target_link_libraries(maple_engine_static PRIVATE Threads::Threads ${CMAKE_DL_LIBS})

    # CUDA (if available)
    find_package(CUDAToolkit)
    if(CUDAToolkit_FOUND)
        target_link_libraries(maple_engine PRIVATE CUDA::cudart CUDA::cublas CUDA::cuda_driver)
        target_link_libraries(maple_engine_static PRIVATE CUDA::cudart CUDA::cublas CUDA::cuda_driver)
        target_compile_definitions(maple_engine PRIVATE GGML_USE_CUDA)
        target_compile_definitions(maple_engine_static PRIVATE GGML_USE_CUDA)
    endif()

    # OpenMP
    find_package(OpenMP)
    if(OpenMP_C_FOUND)
        target_link_libraries(maple_engine PRIVATE OpenMP::OpenMP_C)
        target_link_libraries(maple_engine_static PRIVATE OpenMP::OpenMP_C)
    endif()
endif()

# Demo executable
# add_executable(maple_demo ../demo/maple_demo.cpp)
# target_link_libraries(maple_demo PRIVATE maple_engine_static)
