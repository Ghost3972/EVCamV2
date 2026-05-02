cmake_minimum_required(VERSION 3.22.1)

project("evcam_gles_compositor")

find_program(ZIG_EXECUTABLE zig REQUIRED)
set(EVCAM_ZIG_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../zig/evcam_gles_compositor.zig")

set(EVCAM_ZIG_ANCHOR "${CMAKE_CURRENT_BINARY_DIR}/evcam_zig_anchor.c")
file(WRITE "${EVCAM_ZIG_ANCHOR}" "void evcam_zig_anchor(void) {}\n")
set_source_files_properties(
        "${EVCAM_ZIG_ANCHOR}"
        PROPERTIES OBJECT_DEPENDS "${EVCAM_ZIG_SOURCE}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/zig_android_libc.txt"
        "include_dir=${CMAKE_SYSROOT}/usr/include\n"
        "sys_include_dir=${CMAKE_SYSROOT}/usr/include/aarch64-linux-android\n"
        "crt_dir=${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28\n"
        "msvc_lib_dir=\n"
        "kernel32_lib_dir=\n"
        "gcc_dir=\n")

add_library(
        evcam_gles_compositor
        SHARED
        "${EVCAM_ZIG_ANCHOR}")

add_custom_command(
        TARGET evcam_gles_compositor
        POST_BUILD
        COMMAND ${ZIG_EXECUTABLE} build-lib
                "${EVCAM_ZIG_SOURCE}"
                -target "aarch64-linux-android.28"
                -dynamic
                -fPIC
                -O ReleaseFast
                --name evcam_gles_compositor
                -femit-bin=$<TARGET_FILE:evcam_gles_compositor>
                --libc "${CMAKE_CURRENT_BINARY_DIR}/zig_android_libc.txt"
                -I${CMAKE_SYSROOT}/usr/include
                -I${CMAKE_SYSROOT}/usr/include/aarch64-linux-android
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/liblog.so
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libandroid.so
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libmediandk.so
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libjnigraphics.so
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libEGL.so
                ${CMAKE_SYSROOT}/usr/lib/aarch64-linux-android/28/libGLESv2.so
        VERBATIM)
