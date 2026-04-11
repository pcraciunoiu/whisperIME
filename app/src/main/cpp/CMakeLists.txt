cmake_minimum_required(VERSION 3.22.1)

project(rnnoise_jni)

set(RNNOISE_ROOT ${CMAKE_SOURCE_DIR}/../../../../third_party/rnnoise)

add_library(rnnoise STATIC
    ${RNNOISE_ROOT}/src/denoise.c
    ${RNNOISE_ROOT}/src/rnn.c
    ${RNNOISE_ROOT}/src/pitch.c
    ${RNNOISE_ROOT}/src/kiss_fft.c
    ${RNNOISE_ROOT}/src/celt_lpc.c
    ${RNNOISE_ROOT}/src/nnet.c
    ${RNNOISE_ROOT}/src/nnet_default.c
    ${RNNOISE_ROOT}/src/parse_lpcnet_weights.c
    ${RNNOISE_ROOT}/src/rnnoise_data.c
    ${RNNOISE_ROOT}/src/rnnoise_tables.c
)

target_include_directories(rnnoise PUBLIC
    ${RNNOISE_ROOT}/include
    ${RNNOISE_ROOT}/src
)

# Do NOT define USE_WEIGHTS_FILE: upstream uses #ifndef USE_WEIGHTS_FILE for embedded weights
# (rnnoise_arrays + init_rnnoise). Defining USE_WEIGHTS_FILE=0 still defines the macro and skips
# that path, leaving the model uninitialized. NEON stays enabled (vec.h); the prior crash was
# that init bug, not DISABLE_NEON.
target_compile_definitions(rnnoise PRIVATE
    FLOATING_POINT=1
)

find_library(log_lib log)
find_library(m_lib m)

target_link_libraries(rnnoise ${m_lib})

add_library(rnnoise_jni SHARED rnnoise_jni.cpp)

target_include_directories(rnnoise_jni PRIVATE ${RNNOISE_ROOT}/include)

target_link_libraries(rnnoise_jni rnnoise ${log_lib})
