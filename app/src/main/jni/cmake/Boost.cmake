# Boost comes from the official release archive (cached next to the sources).
set(BOOST_VERSION 1.92.0)
set(BOOST_ARCHIVE "${CMAKE_SOURCE_DIR}/.cache/boost-${BOOST_VERSION}-cmake.tar.xz")
if(NOT EXISTS "${BOOST_ARCHIVE}")
  message(STATUS "Downloading Boost ${BOOST_VERSION}")
  file(DOWNLOAD
    "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "${BOOST_ARCHIVE}"
    EXPECTED_HASH SHA256=9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d)
endif()
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/.cache/boost-${BOOST_VERSION}")
  file(ARCHIVE_EXTRACT INPUT "${BOOST_ARCHIVE}" DESTINATION "${CMAKE_SOURCE_DIR}/.cache")
endif()
set(BOOST_INCLUDE_LIBRARIES algorithm crc dll interprocess preprocessor range regex
    scope_exit signals2 utility uuid vmd)
add_subdirectory("${CMAKE_SOURCE_DIR}/.cache/boost-${BOOST_VERSION}" boost EXCLUDE_FROM_ALL)
