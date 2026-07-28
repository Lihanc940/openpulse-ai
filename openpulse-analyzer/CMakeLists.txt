cmake_minimum_required(VERSION 3.10)
project(openpulse-analyzer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(include)

file(GLOB_RECURSE SOURCES "src/*.cpp")

add_executable(analyzer ${SOURCES})

target_include_directories(analyzer PRIVATE include)