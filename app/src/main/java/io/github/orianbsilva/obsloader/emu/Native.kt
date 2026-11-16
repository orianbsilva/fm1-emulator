// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The emulator's host (src/main/cpp/host.cpp) over JNI.
package io.github.orianbsilva.obsloader.emu

object Native {
    init { System.loadLibrary("fm1host") }

    /** the core's library (libcore_<name>.so): its sim_* API; false if it is not there or misses a function */
    @JvmStatic external fun load(lib: String): Boolean
    /** boot it (the flash as saved, 1 MB, or null: erased) and start the sound */
    @JvmStatic external fun start(flash: ByteArray?)
    @JvmStatic external fun stop()
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun btn(id: Int, down: Boolean)
    @JvmStatic external fun key(n: Int, down: Boolean)
    @JvmStatic external fun enc(role: Int, steps: Int)
    @JvmStatic external fun master(v: Int)
    @JvmStatic external fun midi(st: Int, d1: Int, d2: Int)
    /** the screen (240 x 240 ARGB) and the lights (buttons, keys, buttons' glow, keys' glow); the frame's serial */
    @JvmStatic external fun screen(argb: IntArray, lights: IntArray): Int
    @JvmStatic external fun level(): Float
    @JvmStatic external fun flashIfDirty(dst: ByteArray): Boolean
    @JvmStatic external fun presetName(): String
}
