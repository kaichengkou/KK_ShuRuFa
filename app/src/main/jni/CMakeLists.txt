# Native build: librime and its dependencies, linked statically into one JNI library.
# The approach (in-tree deps, Boost from the official release) follows what Trime does.
cmake_minimum_required(VERSION 3.18.0)
project(kk_rime VERSION 0.1.0)

add_link_options("LINKER:--build-id=none")
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
add_subdirectory(librime)
target_compile_options(rime-static PRIVATE "-Wno-error=deprecated-declarations")

add_subdirectory(kk_rime)
