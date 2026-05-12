cmake_minimum_required(VERSION 3.22.1)
project(httpaceserveproxy LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(HTTPACE_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/httpaceproxycpp)

add_library(httpaceproxy_core STATIC
    ${HTTPACE_CPP_DIR}/src/ace_client.cpp
    ${HTTPACE_CPP_DIR}/src/broadcast.cpp
    ${HTTPACE_CPP_DIR}/src/config.cpp
    ${HTTPACE_CPP_DIR}/src/http_server.cpp
    ${HTTPACE_CPP_DIR}/src/json.cpp
    ${HTTPACE_CPP_DIR}/src/playlist.cpp
    ${HTTPACE_CPP_DIR}/src/plugins.cpp
    ${HTTPACE_CPP_DIR}/src/proxy.cpp
    ${HTTPACE_CPP_DIR}/src/util.cpp
    android_http_client.cpp
)

target_include_directories(httpaceproxy_core PUBLIC
    ${HTTPACE_CPP_DIR}/include
)

target_compile_definitions(httpaceproxy_core PUBLIC HTTPACEPROXYCPP_ANDROID=1)

add_library(httpaceproxy_jni SHARED
    httpaceproxy_jni.cpp
)

target_link_options(httpaceproxy_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(httpaceproxy_jni
    httpaceproxy_core
    android
    log
    z
)
