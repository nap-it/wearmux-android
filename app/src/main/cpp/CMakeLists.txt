cmake_minimum_required(VERSION 3.22.1)

project(whisper_peci C CXX)

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)

# Raiz do clone whisper.cpp (v1.7.2)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Sources whisper + ggml (versão CPU apenas, suficiente para Android).
set(WHISPER_SOURCES
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-aarch64.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
)

add_library(whisper SHARED
    ${WHISPER_SOURCES}
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.c
)

# Android 15+ (16 KB page size): ensure ELF PT_LOAD segments are emitted with
# 16 KB max/common page size so the library is flagged as compatible.
target_link_options(whisper PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_include_directories(whisper PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

# Flags de compilação — quando em release, optimizamos agressivamente.
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
    target_link_options(whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

# arm64-v8a: activa NEON + fp16 para ganhar factor ~3× no whisper-tiny.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(whisper PRIVATE -march=armv8.2-a+fp16)
endif()

find_library(log-lib log)
target_link_libraries(whisper ${log-lib} android)
