# Whisper JNI bridge — links whisper.cpp (static) into libwhisper_jni.so
cmake_minimum_required(VERSION 3.18)
project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# app/src/main/cpp -> four levels up to repo root
set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_ROOT}" "${CMAKE_BINARY_DIR}/whisper_build")

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE "${WHISPER_ROOT}/include")

target_link_libraries(whisper_jni PRIVATE whisper android log)

if (ANDROID)
    target_compile_definitions(whisper_jni PRIVATE WHISPER_JNI_ANDROID=1)
endif()
