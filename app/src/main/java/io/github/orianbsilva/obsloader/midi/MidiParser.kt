// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. Whole MIDI messages out of the byte stream Android's MIDI API gives in pieces:
// channel messages (running status too), system common, real time (at once, even inside a SysEx) and SysEx.
package io.github.orianbsilva.obsloader.midi

import java.io.ByteArrayOutputStream

class MidiParser(private val out: (ByteArray) -> Unit) {
    private var status = 0
    private val buf = ByteArray(3)
    private var have = 0
    private var need = 0
    private val sysex = ByteArrayOutputStream(256)
    private var inSysex = false

    private fun lengthOf(st: Int): Int = when {
        st < 0xF0 -> if ((st and 0xE0) == 0xC0) 2 else 3       // Cn, Dn: two bytes; the others three
        st == 0xF1 || st == 0xF3 -> 2
        st == 0xF2 -> 3
        else -> 1                                                // F6 and the undefined ones
    }

    fun feed(b: ByteArray, off: Int, count: Int) {
        for (i in off until off + count) {
            val x = b[i].toInt() and 0xFF
            when {
                x >= 0xF8 -> out(byteArrayOf(x.toByte()))        // real time: at once
                x == 0xF0 -> { inSysex = true; sysex.reset(); sysex.write(x); status = 0 }
                x == 0xF7 -> if (inSysex) { sysex.write(x); inSysex = false; out(sysex.toByteArray()) }
                x >= 0x80 -> {
                    inSysex = false
                    status = x; buf[0] = x.toByte(); have = 1; need = lengthOf(x)
                    if (need == 1) { out(byteArrayOf(x.toByte())); if (x >= 0xF0) status = 0 }
                }
                inSysex -> { sysex.write(x); if (sysex.size() > 65536) inSysex = false }
                status != 0 -> {
                    if (have == 0) { buf[0] = status.toByte(); have = 1 }   // running status
                    buf[have++] = x.toByte()
                    if (have == need) {
                        out(buf.copyOf(need))
                        have = 0
                        if (status >= 0xF0) status = 0                      // (no running status for system common)
                    }
                }
            }
        }
    }
}
