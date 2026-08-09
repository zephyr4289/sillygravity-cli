cmake_minimum_required(VERSION 3.22.1)
project(llama-android-bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Allow finding host-installed CMake packages like SPIRV-Headers during cross-compilation
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
list(APPEND CMAKE_PREFIX_PATH "/usr/share/cmake" "/usr/lib/x86_64-linux-gnu/cmake" "/usr/local/share/cmake")

# Force Vulkan and Adreno tuning
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
add_compile_definitions(GGML_VULKAN)

# OpenCL fallback (Disabled because NDK lacks OpenCL headers by default)
set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL fallback" FORCE)

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
