# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.18.1)

# Declares and names the project.

project("rtmplibrary")
file(GLOB SRC_FILE *.cpp *.h)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library( # Sets the name of the library.
        rtmplibrary
        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        rtmplibrary.cpp
        CxwLog.cpp
        CxwYuv.cpp
        )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)


## 引入头文件目录
include_directories(librtmp )
include_directories(libx264)

# 外部头文件路径，因为我们要引用 libyuv.h
include_directories(libyuv/include)
## 配置librtmp
add_subdirectory(librtmp)
add_subdirectory(libx264)

# 添加子项目，libyuv 作为一个子项目自己编译，有自己的 CMakeList.txt。
# 编译结果存放在 build 目录下，可以在里面找到生成的 .so 文件。
add_subdirectory(libyuv ./build)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        rtmplibrary

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}

        rtmp
        yuv
        )