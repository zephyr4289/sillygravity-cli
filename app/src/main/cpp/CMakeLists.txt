cmake_minimum_required(VERSION 3.22.1)
project(llama-android-bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable GPU backends for CI — NDK sysroot lacks vulkan/vulkan.hpp (C++ headers).
# Vulkan will still work at runtime on real Android devices with Vulkan drivers.
set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan for CI" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "Disable OpenCL" FORCE)

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
