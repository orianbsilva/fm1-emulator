# SPDX-License-Identifier: GPL-3.0-only
# The emulator: a host (fm1host: AAudio, the input queue, the screen and the LEDs for the app, over JNI) and one
# library per firmware core (core_<name>: the firmware's own simulator core, its C sources built for the phone), each
# loaded by name, so cores never share their globals.
cmake_minimum_required(VERSION 3.22)
project(fm1emu C CXX)

# ObsSynth FM-1 (cores/obssynth-fm1: tests/sim_core.c, the firmware's sources and generated tables; GPL-3.0)
set(OBS cores/obssynth-fm1)
file(STRINGS ${OBS}/VERSION OBS_VERSION LIMIT_COUNT 1)
file(STRINGS ${OBS}/COMMIT OBS_COMMIT LIMIT_COUNT 1)
add_library(core_obssynth_fm1 SHARED ${OBS}/tests/sim_core.c)
target_include_directories(core_obssynth_fm1 PRIVATE ${OBS}/gen ${OBS}/firmware/src ${OBS}/tests)
target_compile_definitions(core_obssynth_fm1 PRIVATE SYNTH_VERSION="${OBS_VERSION}" SYNTH_BUILD="${OBS_COMMIT}")
target_compile_options(core_obssynth_fm1 PRIVATE -O2 -w -fno-strict-aliasing)

add_library(fm1host SHARED host.cpp)
target_compile_options(fm1host PRIVATE -O2 -Wall)
target_link_libraries(fm1host aaudio log dl)
