add_library(kk_rime SHARED kk_rime.cc kk_core.cc kk_corrector.cc)
target_link_libraries(kk_rime rime-static libopencc)
if(ANDROID)
  target_link_libraries(kk_rime log)
endif()
target_include_directories(kk_rime PRIVATE
  "${CMAKE_BINARY_DIR}/librime/src" "${CMAKE_SOURCE_DIR}/librime/src" "${CMAKE_BINARY_DIR}/include")

# Host-only smoke test: deploys the bundled schema and types a few pinyin strings.
option(KK_HOST_TEST "Build the host test program" OFF)
if(KK_HOST_TEST AND NOT ANDROID)
  add_executable(kk_rime_test host_test.cc kk_corrector.cc)
  target_link_libraries(kk_rime_test rime-static libopencc)
  target_include_directories(kk_rime_test PRIVATE "${CMAKE_SOURCE_DIR}/librime/src" "${CMAKE_BINARY_DIR}/librime/src")
endif()
