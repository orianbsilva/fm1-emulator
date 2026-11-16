# SPDX-License-Identifier: GPL-3.0-only
# The emulator's host (fm1host: AAudio, the input queue, the screen and the LEDs for the app, over JNI). The emulator
# itself is fm1-emu's (Joakim Eriksson, MIT): its fm1-web library built for Android by tools/build_fm1emu.sh into
# src/main/jniLibs/<abi>/libfm1_web.so, loaded by name.
cmake_minimum_required(VERSION 3.22)
project(fm1emu CXX)
add_library(fm1host SHARED host.cpp)
target_compile_options(fm1host PRIVATE -O2 -Wall)
target_link_libraries(fm1host aaudio log dl)
