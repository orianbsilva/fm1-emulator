// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. The emulator's host (src/main/cpp/host.cpp) over JNI; the FM-1 is fm1-emu's
// (Joakim Eriksson, MIT), libfm1_web.so.
package io.github.orianbsilva.obsloader.emu

object Native {
    init { System.loadLibrary("fm1host") }

    /** fm1-emu's library; false if it is missing or misses a function */
    @JvmStatic external fun load(): Boolean
    /** boot a package with its saved flash sectors (or null); chip 1: the newer flash part.
     *  0 booted, 1 not an FM-1 package, 2 the saved flash did not fit, 3 an SMK-37 package */
    @JvmStatic external fun boot(pkg: ByteArray, sectors: ByteArray?, chip: Int): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    /** by matrix id: OCT- 0, OCT+ 1, FX..GLO 2..7, HOME..REC 8..13, keys F3..G5 14..40 */
    @JvmStatic external fun key(id: Int, down: Boolean)
    /** SELECT 0, ALGORITHM 1, KNOB1..4 2..5, PRESETS 6 */
    @JvmStatic external fun enc(enc: Int, clicks: Int)
    @JvmStatic external fun master(v: Int)
    /** a whole MIDI message into the MIDI IN jack */
    @JvmStatic external fun midi(msg: ByteArray)
    /** the screen (240 x 240 ARGB) and the LEDs (41 levels 0..1 by matrix id); the frame's serial */
    @JvmStatic external fun screen(argb: IntArray, leds: FloatArray): Int
    @JvmStatic external fun level(): Float
    @JvmStatic external fun halted(): Boolean
    @JvmStatic external fun late(): Int
    @JvmStatic external fun flashRevision(): Int
    /** the flash sectors the firmware changed (fm1-emu's save format), or null while it writes */
    @JvmStatic external fun sectors(): ByteArray?
}
