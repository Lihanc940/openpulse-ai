cmake_minimum_required(VERSION 3.20)
project(openpulse-analyzer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(include)

add_executable(openpulse-analyzer src/main.cpp "include/FileScanner.h" "src/FileScanner.cpp")
target_include_directories(openpulse-analyzer PRIVATE include)
