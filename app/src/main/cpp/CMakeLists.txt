cmake_minimum_required(VERSION 3.22.1)
project(soundfix LANGUAGES CXX)

add_library(soundfix SHARED soundfix.cpp)

target_compile_options(soundfix PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti)
target_link_libraries(soundfix log)
