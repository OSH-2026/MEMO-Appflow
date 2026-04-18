cmake_minimum_required(VERSION 3.14)
project(TransformerPredictor VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_BUILD_TYPE Release)

# OpenMP is optional; by default we run single-threaded
find_package(OpenMP)
if(OpenMP_CXX_FOUND)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
endif()

include_directories(${CMAKE_SOURCE_DIR}/include)

set(SOURCES
    src/tensor.cpp
    src/ops.cpp
    src/weight_loader.cpp
    src/model.cpp
    src/inferencer.cpp
    src/transformer_predictor.cpp
)

add_library(transformer STATIC ${SOURCES})

if(OpenMP_CXX_FOUND)
    target_link_libraries(transformer OpenMP::OpenMP_CXX)
endif()

add_executable(example example.cpp)
target_link_libraries(example transformer)

install(TARGETS transformer DESTINATION lib)
install(FILES 
    include/tensor.h
    include/ops.h
    include/weight_loader.h
    include/model.h
    include/inferencer.h
    include/transformer_predictor.h
    DESTINATION include/transformer
)
