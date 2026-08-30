cmake_minimum_required(VERSION 3.22.1)

project(ground_filter LANGUAGES CXX)

add_library(
    ground_filter
    SHARED
    ground_filter.cpp
    ground_filter_jni.cpp
)

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(ground_filter PRIVATE ground_filter_test_jni.cpp)
endif()

target_compile_features(ground_filter PRIVATE cxx_std_17)

target_compile_options(
    ground_filter
    PRIVATE
    -O3
    -Wall
    -Wextra
    -Wpedantic
)
