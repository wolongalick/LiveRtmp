cmake_minimum_required(VERSION 3.18.1)

project("librtmp")

# 只要有一个C++文件，就是属于C++ == CXX CMAKE_CXX_FLAGS
# 不修改源码的情况下，解决报错，添加宏  -D
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DNO_CRYPTO")

file(GLOB SRC_FILE *.c)

add_library(rtmp SHARED ${SRC_FILE})