# Points librime at the in-tree Boost targets.
set(Boost_FOUND TRUE)
list(TRANSFORM BOOST_INCLUDE_LIBRARIES PREPEND Boost:: OUTPUT_VARIABLE Boost_LIBRARIES)
file(GLOB __boost_libs LIST_DIRECTORIES true "${CMAKE_SOURCE_DIR}/.cache/boost-${BOOST_VERSION}/libs/*")
foreach(__lib ${__boost_libs})
  if(IS_DIRECTORY "${__lib}/include")
    list(APPEND Boost_INCLUDE_DIRS "${__lib}/include")
  endif()
endforeach()
