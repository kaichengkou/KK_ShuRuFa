# Added to app/src/main/jni/CMakeLists.txt (KK_BRIDGE_DIR) in place of the JNI bridge, so the iOS
# build compiles librime and its dependencies exactly as the Android build does.
set(KK_JNI_DIR "${CMAKE_SOURCE_DIR}/kk_rime")

add_library(kk_rime_ios STATIC kk_rime_c.cc "${KK_JNI_DIR}/kk_core.cc" "${KK_JNI_DIR}/kk_corrector.cc")
target_link_libraries(kk_rime_ios rime-static libopencc)
# librime's headers need C++17 (std::filesystem); its own CMAKE_CXX_STANDARD stays inside its folder
target_compile_features(kk_rime_ios PUBLIC cxx_std_17)
target_include_directories(kk_rime_ios
  PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
  PRIVATE "${KK_JNI_DIR}" "${CMAKE_BINARY_DIR}/librime/src" "${CMAKE_SOURCE_DIR}/librime/src" "${CMAKE_BINARY_DIR}/include")

# on the build machine: deploys the data the app ships (see build_rime_data.sh)
if(NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
  add_executable(kk_rime_deploy kk_deploy.cc)
  target_link_libraries(kk_rime_deploy kk_rime_ios)
endif()
