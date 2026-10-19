cmake_minimum_required(VERSION 3.22.1)
project(xena_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
  llamacpp
  GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
  GIT_TAG        b4604
  GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llamacpp)

add_library(xena_jni SHARED jni_bridge.cpp)
target_include_directories(xena_jni PRIVATE
  ${llamacpp_SOURCE_DIR}/include
  ${llamacpp_SOURCE_DIR}/ggml/include
)

# Upstream static targets
if (TARGET llama)
  target_link_libraries(xena_jni PRIVATE llama)
endif()
if (TARGET ggml)
  target_link_libraries(xena_jni PRIVATE ggml)
endif()
if (TARGET ggml-base)
  target_link_libraries(xena_jni PRIVATE ggml-base)
endif()
if (TARGET ggml-cpu)
  target_link_libraries(xena_jni PRIVATE ggml-cpu)
endif()

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(xena_jni PRIVATE ${log-lib} ${android-lib})

set_target_properties(xena_jni PROPERTIES OUTPUT_NAME "llama")
