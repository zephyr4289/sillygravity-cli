cmake_minimum_required(VERSION 3.22.1)

if(ANDROID)
    set(ANDROID_PLATFORM android-28 CACHE STRING "" FORCE)
endif()

project(llama-android-bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Allow finding host-installed CMake packages like SPIRV-Headers during cross-compilation
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
list(APPEND CMAKE_PREFIX_PATH "/usr/share/cmake" "/usr/lib/x86_64-linux-gnu/cmake" "/usr/local/share/cmake")

# Fix missing vulkan/vulkan.hpp in Android NDK sysroot
include(FetchContent)

# 1. Fetch Vulkan C++ Headers (vulkan/vulkan.hpp)
FetchContent_Declare(
    vulkan_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG        v1.3.296
)
FetchContent_MakeAvailable(vulkan_headers)

# 2. Fetch SPIR-V C++ Headers (spirv/unified1/spirv.hpp)
FetchContent_Declare(
    spirv_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
    GIT_TAG        main
)
FetchContent_MakeAvailable(spirv_headers)

# 3. Supply include directories to NDK cross-compiler
include_directories(
    ${vulkan_headers_SOURCE_DIR}/include
    ${spirv_headers_SOURCE_DIR}/include
)

# 4. Generate SPIRV-HeadersConfig.cmake for CMake CONFIG mode
file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/cmake_modules")
file(WRITE "${CMAKE_BINARY_DIR}/cmake_modules/SPIRV-HeadersConfig.cmake" "
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV_HEADERS_FOUND TRUE)
set(SPIRV-Headers_INCLUDE_DIRS \"${spirv_headers_SOURCE_DIR}/include\")
set(SPIRV_HEADERS_INCLUDE_DIRS \"${spirv_headers_SOURCE_DIR}/include\")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED GLOBAL)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\"
    )
endif()
if(NOT TARGET SPIRV-Headers)
    add_library(SPIRV-Headers INTERFACE IMPORTED GLOBAL)
    set_target_properties(SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\"
    )
endif()
")

# Point CMake Config lookup directly to our generated package directory
set(SPIRV-Headers_DIR "${CMAKE_BINARY_DIR}/cmake_modules" CACHE PATH "" FORCE)

# 5. Enforce Vulkan GPU acceleration
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan" FORCE)
add_compile_definitions(GGML_VULKAN)

# OpenCL fallback (Disabled because NDK lacks OpenCL headers by default)
set(GGML_OPENCL OFF CACHE BOOL "Enable OpenCL fallback" FORCE)

# Assume llama.cpp is present in the source tree
add_subdirectory(llama.cpp)

if(TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
        ${spirv_headers_SOURCE_DIR}/include
    )
else()
    message(FATAL_ERROR "ggml-vulkan target not found - check target name in ggml-vulkan/CMakeLists.txt for this llama.cpp version")
endif()

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
