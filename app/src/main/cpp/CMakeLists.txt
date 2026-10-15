# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.18.1)

message(STATUS "Android NDK version: ${CMAKE_ANDROID_NDK_VERSION}")
message(STATUS "Android NDK path: ${CMAKE_ANDROID_NDK}")

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("envset")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        envset.cpp)

# Serial (UART) + SPI JNI driver for the 40-pin demo.
add_library(io SHARED
        serialspi.cpp)

# GPIO (sysfs /sys/class/gpio) JNI driver for the 40-pin demo.
add_library(gpio SHARED
        gpio.cpp)

# I2C (i2c-dev /dev/i2c-N) JNI driver for the 40-pin demo.
add_library(i2c SHARED
        i2c.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library

        android
        log)

target_link_libraries(io
        android
        log)
target_link_libraries(gpio
        android
        log)

target_link_libraries(
        i2c
        android
        log)