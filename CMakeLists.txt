cmake_minimum_required(VERSION 3.22.1)
project(llama-android-bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Force Vulkan and Adreno tuning
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
add_compile_definitions(GGML_VULKAN)

# OpenCL fallback
set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL fallback" FORCE)
add_compile_definitions(GGML_OPENCL)

# Assume llama.cpp is present in the source tree
add_subdirectory(llama.cpp)

add_library(llama-bridge SHARED LlamaBridge.cpp)

target_include_directories(llama-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(llama-bridge
    llama
    ggml
    log
    android
)
