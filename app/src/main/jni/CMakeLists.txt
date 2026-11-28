# Native build: librime and its dependencies, linked statically into one JNI library.
# The approach (in-tree deps, Boost from the official release) follows what Trime does.
cmake_minimum_required(VERSION 3.18.0)
project(kk_rime VERSION 0.1.0)

if(NOT APPLE)
  add_link_options("LINKER:--build-id=none")
endif()
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)
option(ENABLE_LOGGING "" ON)

include(Boost)

set(SPDLOG_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(SPDLOG_BUILD_PIC ON CACHE BOOL "" FORCE)
set(SPDLOG_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(SPDLOG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SPDLOG_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(librime/deps/spdlog EXCLUDE_FROM_ALL)

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(librime/deps/opencc)
# opencc's data/ runs the freshly built (Android) opencc_dict, which cannot run here.
set_property(DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/librime/deps/opencc/data"
             PROPERTY EXCLUDE_FROM_ALL TRUE)
file(GLOB LIBOPENCC_HEADERS librime/deps/opencc/src/*.hpp)
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")

include_directories("${CMAKE_SOURCE_DIR}/librime/include")

# plugins are linked statically, so they live inside librime/plugins
set(RIME_PLUGINS librime-lua librime-octagram)
foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
    file(CREATE_LINK "${CMAKE_SOURCE_DIR}/${plugin}"
         "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}" COPY_ON_ERROR SYMBOLIC)
  endif()
endforeach()
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty")
  file(CREATE_LINK "${CMAKE_SOURCE_DIR}/librime-lua-deps"
       "${CMAKE_SOURCE_DIR}/librime/plugins/librime-lua/thirdparty" COPY_ON_ERROR SYMBOLIC)
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
# 临时模式: librime learns from every commit and has no switch for it. One guard in Memory::OnCommit
# lets the option _kk_no_learning stop it. Applied here (idempotently) so the submodule stays as is.
set(KK_MEMORY_CC "${CMAKE_CURRENT_SOURCE_DIR}/librime/src/rime/gear/memory.cc")
file(READ "${KK_MEMORY_CC}" KK_MEMORY_SRC)
string(FIND "${KK_MEMORY_SRC}" "_kk_no_learning" KK_PATCHED)
if(KK_PATCHED EQUAL -1)
  string(REPLACE "void Memory::OnCommit(Context* ctx) {\n  if (!user_dict_ || user_dict_->readonly())"
                 "void Memory::OnCommit(Context* ctx) {\n  if (ctx && ctx->get_option(\"_kk_no_learning\"))\n    return;\n  if (!user_dict_ || user_dict_->readonly())"
                 KK_MEMORY_SRC "${KK_MEMORY_SRC}")
  string(FIND "${KK_MEMORY_SRC}" "_kk_no_learning" KK_PATCHED)
  if(KK_PATCHED EQUAL -1)
    message(FATAL_ERROR "could not add the no-learning guard to librime's memory.cc")
  endif()
  file(WRITE "${KK_MEMORY_CC}" "${KK_MEMORY_SRC}")
endif()
add_subdirectory(librime)
target_compile_options(rime-static PRIVATE "-Wno-error=deprecated-declarations")

# The iOS keyboard builds the same engine with its own C bridge (ios/RimeKit) instead of the JNI one.
if(KK_BRIDGE_DIR)
  add_subdirectory("${KK_BRIDGE_DIR}" kk_bridge)
else()
  add_subdirectory(kk_rime)
endif()

# Voice input: whisper.cpp (ggml on the CPU) in its own library, loaded only when used.
if(ANDROID)
  set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
  set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
  set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
  set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dot product and fp16 arithmetic: every arm64 phone from about 2018 on has them
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
  endif()
  add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)
  add_library(kk_whisper SHARED kk_whisper/kk_whisper.cc)
  target_link_libraries(kk_whisper whisper log)
  target_compile_options(kk_whisper PRIVATE -O3)
endif()
