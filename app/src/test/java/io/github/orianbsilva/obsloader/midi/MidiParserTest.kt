// SPDX-License-Identifier: GPL-3.0-only
package io.github.orianbsilva.obsloader.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg chunks: IntArray): List<List<Int>> {
        val got = mutableListOf<List<Int>>()
        val p = MidiParser { m -> got += m.map { it.toInt() and 0xFF } }
        for (c in chunks) { val b = ByteArray(c.size) { c[it].toByte() }; p.feed(b, 0, b.size) }
        return got
    }

    @Test fun notesInPiecesAndRunningStatus() = assertEquals(
        listOf(listOf(0x90, 60, 100), listOf(0x90, 64, 90), listOf(0x80, 60, 0)),
        parse(intArrayOf(0x90, 60), intArrayOf(100, 64, 90), intArrayOf(0x80, 60, 0)))

    @Test fun twoByteMessagesAndRealTime() = assertEquals(
        listOf(listOf(0xC0, 5), listOf(0xF8), listOf(0xD0, 33), listOf(0xD0, 34)),
        parse(intArrayOf(0xC0, 5, 0xF8, 0xD0, 33, 34)))

    @Test fun sysexWithAClockInside() = assertEquals(
        listOf(listOf(0xF8), listOf(0xF0, 1, 2, 3, 0xF7), listOf(0xB0, 1, 64)),
        parse(intArrayOf(0xF0, 1, 2), intArrayOf(0xF8, 3, 0xF7, 0xB0, 1, 64)))
}
