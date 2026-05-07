cmake_minimum_required(VERSION 3.22.1)

project("captionburn" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -----------------------------------------------------------------------------
# whisper.cpp integration
#
# Run scripts/init-whisper.ps1 (Windows) or scripts/init-whisper.sh (POSIX)
# once after cloning this repo to populate third_party/whisper.cpp.
# -----------------------------------------------------------------------------
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp")

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp source not found at ${WHISPER_DIR}.\n"
        "Run scripts/init-whisper.ps1 (Windows) or scripts/init-whisper.sh "
        "(macOS/Linux) from the project root, then sync again.")
endif()

# Disable everything we don't need from whisper.cpp's own build.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# ARMv8 / arm64 path enables NEON + dotprod (post v8.2). We let whisper.cpp's
# own toolchain detection decide; on armeabi-v7a, NEON is on by default.
add_subdirectory("${WHISPER_DIR}" whisper.cpp_build EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------------
# JNI bridge library
# -----------------------------------------------------------------------------
add_library(captionburn SHARED whisper-jni.cpp)

target_include_directories(captionburn PRIVATE
    "${WHISPER_DIR}"
    "${WHISPER_DIR}/include"
    "${WHISPER_DIR}/ggml/include"
    "${WHISPER_DIR}/ggml/src")

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(captionburn
    whisper
    ${log-lib}
    ${android-lib})

if(ANDROID)
    target_link_options(captionburn PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
endif()

# Hot path: aggressive optimisation
target_compile_options(captionburn PRIVATE
    -O3
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden)
