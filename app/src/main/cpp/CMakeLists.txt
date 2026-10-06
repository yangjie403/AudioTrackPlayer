cmake_minimum_required(VERSION 3.22.1)

project(audio_track_player)

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

# FFmpeg is linked directly from the copied static archives under
# app/src/main/cpp/ffmpeg/static/<abi>. The JNI library owns the FFmpeg API
# calls, so the ffmpeg-android directory is not needed at build time.
add_library(ffmpeg_jni SHARED ffmpeg_jni.cpp)

set(FFMPEG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg)
set(FFMPEG_INCLUDE_DIR ${FFMPEG_ROOT}/include)
set(FFMPEG_LIB_DIR ${FFMPEG_ROOT}/static/${ANDROID_ABI})

target_include_directories(
        ffmpeg_jni
        PRIVATE
        ${FFMPEG_INCLUDE_DIR}
)

set(FFMPEG_STATIC_LIBS
        ${FFMPEG_LIB_DIR}/libavfilter.a
        ${FFMPEG_LIB_DIR}/libavformat.a
        ${FFMPEG_LIB_DIR}/libavcodec.a
        ${FFMPEG_LIB_DIR}/libswresample.a
        ${FFMPEG_LIB_DIR}/libswscale.a
        ${FFMPEG_LIB_DIR}/libavutil.a
        ${FFMPEG_LIB_DIR}/libvorbisenc.a
        ${FFMPEG_LIB_DIR}/libvorbis.a
        ${FFMPEG_LIB_DIR}/libopus.a
        ${FFMPEG_LIB_DIR}/libmp3lame.a
        ${FFMPEG_LIB_DIR}/libogg.a
)

target_link_libraries(
        oboe_mp3_player
    PRIVATE
        oboe::oboe
        ${log-lib}
)

target_link_libraries(
        ffmpeg_jni
        PRIVATE
        "-Wl,--start-group"
        ${FFMPEG_STATIC_LIBS}
        "-Wl,--end-group"
        ${log-lib}
        m
        z
        dl
        android
)

target_link_options(
        ffmpeg_jni
        PRIVATE
        "-Wl,-Bsymbolic"
        "-Wl,-z,max-page-size=16384"
)
