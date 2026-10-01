cmake_minimum_required(VERSION 3.22.1)
project(memoflow_firered LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT FIRERED_SRC_BUNDLE)
    message(FATAL_ERROR "FIRERED_SRC_BUNDLE is required")
endif()
if(NOT NCNN_BUNDLE_DIR)
    message(FATAL_ERROR "NCNN_BUNDLE_DIR is required")
endif()

file(GLOB_RECURSE NCNN_CONFIGS
    "${NCNN_BUNDLE_DIR}/*ncnnConfig.cmake"
)
set(NCNN_CONFIG "")
foreach(candidate IN LISTS NCNN_CONFIGS)
    if(candidate MATCHES "/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig\\.cmake$")
        set(NCNN_CONFIG "${candidate}")
        break()
    endif()
endforeach()
if(NOT NCNN_CONFIG)
    message(FATAL_ERROR "Unable to find ncnnConfig.cmake for ABI ${ANDROID_ABI} in ${NCNN_BUNDLE_DIR}")
endif()

get_filename_component(ncnn_DIR "${NCNN_CONFIG}" DIRECTORY)
find_package(ncnn REQUIRED CONFIG PATHS "${ncnn_DIR}" NO_DEFAULT_PATH)

file(GLOB_RECURSE FIRERED_STREAM_SOURCES
    "${FIRERED_SRC_BUNDLE}/*firered_vad_stream_packed.cpp"
)
list(FILTER FIRERED_STREAM_SOURCES INCLUDE REGEX "runtime/ncnn/csrc/firered_vad_stream_packed\\.cpp$")
list(LENGTH FIRERED_STREAM_SOURCES FIRERED_STREAM_COUNT)
if(FIRERED_STREAM_COUNT LESS 1)
    message(FATAL_ERROR "FireRed stream runtime source not found")
endif()
list(GET FIRERED_STREAM_SOURCES 0 FIRERED_STREAM_SOURCE)

get_filename_component(FIRERED_CSRC_DIR "${FIRERED_STREAM_SOURCE}" DIRECTORY)
get_filename_component(FIRERED_NCNN_DIR "${FIRERED_CSRC_DIR}" DIRECTORY)
get_filename_component(FIRERED_RUNTIME_DIR "${FIRERED_NCNN_DIR}" DIRECTORY)
set(FIRERED_COMMON_DIR "${FIRERED_RUNTIME_DIR}/common")
set(FIRERED_FFT_SOURCE "${FIRERED_COMMON_DIR}/frontend/fft.cc")

add_library(
    memoflow_firered
    SHARED
    firered_jni.cpp
    aaudio_benchmark_jni.cpp
    "${FIRERED_STREAM_SOURCE}"
    "${FIRERED_FFT_SOURCE}"
)

target_include_directories(
    memoflow_firered
    PRIVATE
    "${FIRERED_CSRC_DIR}"
    "${FIRERED_COMMON_DIR}"
)

target_link_libraries(
    memoflow_firered
    ncnn
    log
    aaudio
)
