cmake_minimum_required(VERSION 3.22.1)

project(oboe_mp3_player)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(oboe_mp3_player SHARED oboe_mp3_player.cpp)

target_include_directories(
    oboe_mp3_player
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

find_library(log-lib log)

target_link_libraries(
    oboe_mp3_player
    PRIVATE
    oboe::oboe
    ${log-lib}
)
