// SPDX-License-Identifier: GPL-3.0-only
package io.github.orianbsilva.obsloader.fm1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun pack7RoundTrip() {
        val d = hex(Vectors.DATA)
        assertArrayEquals(d, Wire.unpack7(Wire.pack7(d)).copyOf(d.size))
    }

    @Test fun responseIsTheReference() =
        assertArrayEquals(hex(Vectors.RESPONSE), Wire.response(0x12345, hex(Vectors.DATA), fl = 3))

    @Test fun requestParses() {
        assertEquals(Request(2, 0x1F400, 512), Wire.parseRequest(hex(Vectors.REQUEST)))
        assertNull(Wire.parseRequest(hex(Vectors.IDENT_APP)))
    }

    @Test fun identities() {
        val app = Wire.parseIdentity(hex(Vectors.IDENT_APP))!!
        assertEquals("FM-1_627010", app.text)
        assertEquals("FM-1", app.model)
        assertFalse(app.loader)
        val ldr = Wire.parseIdentity(hex(Vectors.IDENT_LOADER))!!
        assertTrue(ldr.loader)
        assertEquals("FM-1", Wire.modelOf(ldr.text))
    }

    @Test fun packageHead() {
        val head = hex(Vectors.PKG_HEAD)
        assertEquals(Vectors.PKG_PRODUCT, Wire.productOf(head))
        assertArrayEquals(hex(Vectors.PKG_HEAD_IMAGE), Wire.logicalImage(head))
    }

    @Test fun sysexAssembler() {
        val got = mutableListOf<ByteArray>()
        val a = SysexAssembler { got += it }
        val r = hex(Vectors.REQUEST)
        a.feed(byteArrayOf(0x90.toByte(), 60, 100), 0, 3)            // a note: not ours
        a.feed(r, 0, 5)
        a.feed(byteArrayOf(0xF8.toByte()), 0, 1)                      // a clock in the middle: dropped
        a.feed(r, 5, r.size - 5)
        assertEquals(1, got.size)
        assertArrayEquals(r, got[0])
    }
}
