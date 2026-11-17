cmake_minimum_required(VERSION 3.22)
project(stockfish CXX)

set(CMAKE_CXX_STANDARD 17)

# The engine is shared with the iOS build and stays unmodified (see NOTICE.md).
set(SF ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ios/Vendor/Stockfish)

file(GLOB SF_SRC
    ${SF}/src/*.cpp
    ${SF}/src/nnue/*.cpp
    ${SF}/src/nnue/features/*.cpp
    ${SF}/src/syzygy/*.cpp)
list(REMOVE_ITEM SF_SRC ${SF}/src/main.cpp)

add_library(stockfish SHARED ${SF_SRC} ${SF}/bridge/sfbridge.cpp sfjni.cpp)
target_include_directories(stockfish PRIVATE ${SF}/src ${SF}/bridge/include)

# Mirrors ios/Package.swift and the Makefile's armv8 / sse41-popcnt targets.
target_compile_definitions(stockfish PRIVATE NDEBUG NNUE_EMBEDDING_OFF IS_64BIT USE_PTHREADS USE_POPCNT)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # No dotprod: devices without it would fault on the first search.
    target_compile_definitions(stockfish PRIVATE USE_NEON=8)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(stockfish PRIVATE USE_SSE41 USE_SSSE3 USE_SSE2)
    target_compile_options(stockfish PRIVATE -msse4.1 -mssse3 -mpopcnt)
endif()

target_link_libraries(stockfish log)
