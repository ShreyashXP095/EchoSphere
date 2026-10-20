cmake_minimum_required(VERSION 3.22.1)
project(echosphere_audio CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# 1. Fetch Google Oboe (AAudio/OpenSL ES low-latency audio library)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.8.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(oboe)

# 2. Fetch Xiph libopus (Low-latency audio codec)
FetchContent_Declare(
    libopus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG v1.4
    GIT_SHALLOW TRUE
)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Disable opus programs" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Disable opus testing" FORCE)
FetchContent_MakeAvailable(libopus)

# 3. EchoSphere Native Audio Library
add_library(
    echosphere_audio
    SHARED
    jni_bridge.cpp
    OpusCodec.cpp
    JitterBuffer.cpp
    AudioEngine.cpp
    AcousticCalibrator.cpp
)

target_include_directories(
    echosphere_audio
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${libopus_SOURCE_DIR}/include
    ${oboe_SOURCE_DIR}/include
)

target_link_libraries(
    echosphere_audio
    PRIVATE
    oboe
    opus
    android
    log
)
