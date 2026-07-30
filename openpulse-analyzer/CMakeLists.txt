cmake_minimum_required(VERSION 3.20)
project(openpulse-analyzer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
  nlohmann_json
  GIT_REPOSITORY https://github.com/nlohmann/json.git
  GIT_TAG v3.11.3
)
FetchContent_MakeAvailable(nlohmann_json)

add_executable(openpulse-analyzer
  src/main.cpp
  src/Analyzer.cpp
)
target_include_directories(openpulse-analyzer PRIVATE include)
if(MSVC)
  target_compile_options(openpulse-analyzer PRIVATE /utf-8)
endif()
target_link_libraries(openpulse-analyzer PRIVATE nlohmann_json::nlohmann_json)
