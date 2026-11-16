// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The FM-1's update protocol, after Felucca's installer (tools/fm1_install.py in
// obssynth-fm1, (C) 2026 Leo Kuroshita, Hügelton Instruments, GPL-3.0): the handshake, the identity, the read requests
// the device sends during an update and our answers, and the package (.fwsc) and its logical image.
package io.github.orianbsilva.obsloader.fm1

import java.security.MessageDigest

/** what a running FM-1 (or its update loader) says it is: "FM-1_627010", "ota-FM-1_900" */
data class Identity(val text: String, val model: String, val version: Long) {
    /** the update loader: an update was started and not finished */
    val loader: Boolean get() = model.lowercase().startsWith("ota-")
}

/** a read request: the device wants len bytes of the logical image at addr */
data class Request(val flags: Int, val addr: Long, val len: Int)

object Wire {
    val HS_QUERY = bytes(0xF0, 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7F, 0xF7)
    val UPGRADE = bytes(0xF0, 0x22, 0x24, 0x35, 0x7F, 0xF7)
    const val FINISH_CHECK = 0xE0000000L
    const val FINISH_WRITE = 0xF0000000L
    const val MAXDATA = 512
    private const val BLOCKS = 20
    private const val BLK = 0x30
    private const val KEEP = 0x2F
    val LOADER_MARK = "FELUCCA-LOADER-1".toByteArray(Charsets.US_ASCII)
    /** the unmodified official FM-1 V15 (FM-1.fwsc / FM-1_015.fwsc from M-VAVE) */
    const val STOCK_V15_SHA256 = "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"
    private val IDENT = Regex("([^_]+)_(\\d+)")

    fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    fun pack7(data: ByteArray, from: Int = 0, to: Int = data.size): ByteArray {
        val out = java.io.ByteArrayOutputStream((to - from) * 8 / 7 + 2)
        var acc = 0L
        var nb = 0
        for (i in from until to) {
            acc = acc or ((data[i].toLong() and 0xFF) shl nb)
            nb += 8
            while (nb >= 7) {
                out.write((acc and 0x7F).toInt())
                acc = acc ushr 7
                nb -= 7
            }
        }
        if (nb > 0) out.write((acc and 0x7F).toInt())
        return out.toByteArray()
    }

    fun unpack7(s: ByteArray, from: Int = 0, to: Int = s.size): ByteArray {
        val out = java.io.ByteArrayOutputStream((to - from) * 7 / 8 + 1)
        var acc = 0L
        var nb = 0
        for (i in from until to) {
            acc = acc or ((s[i].toLong() and 0x7F) shl nb)
            nb += 7
            while (nb >= 8) {
                out.write((acc and 0xFF).toInt())
                acc = acc ushr 8
                nb -= 8
            }
        }
        return out.toByteArray()
    }

    /** our answer to a read request: data of the image at addr */
    fun response(addr: Long, data: ByteArray, from: Int = 0, to: Int = data.size, fl: Int = 0): ByteArray {
        val n = to - from
        val body = java.io.ByteArrayOutputStream(n + 16)
        body.write(0x00); body.write(0x59); body.write(0x30)
        body.write((n + 8) and 0xFF); body.write(((n + 8) shr 8) and 0xFF)
        body.write(0); body.write(fl and 0xFF)
        for (k in 0 until 4) body.write(((addr ushr (8 * k)) and 0xFF).toInt())
        body.write(n and 0xFF); body.write((n shr 8) and 0xFF); body.write(0)
        body.write(data, from, n)
        val b = body.toByteArray()
        var sum = 0
        for (i in 6 until b.size) sum += b[i].toInt() and 0xFF
        val all = b + byteArrayOf((sum.inv() and 0xFF).toByte())
        return byteArrayOf(0xF0.toByte()) + pack7(all) + byteArrayOf(0xF7.toByte())
    }

    private fun framed(pkt: ByteArray) =
        pkt.size >= 2 && (pkt[0].toInt() and 0xFF) == 0xF0 && (pkt[pkt.size - 1].toInt() and 0xFF) == 0xF7

    fun parseRequest(pkt: ByteArray): Request? {
        if (!framed(pkt)) return null
        val u = unpack7(pkt, 1, pkt.size - 1)
        if (u.size != 15 || u[0].toInt() != 0x00 || u[1].toInt() != 0x59 || u[2].toInt() != 0x30) return null
        var sum = 0
        for (i in 6 until 14) sum += u[i].toInt() and 0xFF
        if ((sum.inv() and 0xFF) != (u[14].toInt() and 0xFF)) return null
        fun le(a: Int, n: Int): Long {
            var v = 0L
            for (k in 0 until n) v = v or ((u[a + k].toLong() and 0xFF) shl (8 * k))
            return v
        }
        return Request(u[6].toInt() and 0xFF, le(7, 4), le(11, 3).toInt())
    }

    fun parseIdentity(pkt: ByteArray): Identity? {
        if (!framed(pkt)) return null
        val d = unpack7(pkt, 1, pkt.size - 1)
        if (d.size != 34 || d[0].toInt() != 0x00 || d[1].toInt() != 0x59 || d[2].toInt() != 0x11) return null
        var end = 33
        while (end > 6 && d[end - 1].toInt() == 0) end--
        val txt = String(d, 6, end - 6, Charsets.ISO_8859_1)
        val m = IDENT.matchEntire(txt) ?: return null
        val v = m.groupValues[2].toLongOrNull() ?: return null
        return Identity(txt, m.groupValues[1], v)
    }

    /** the package's identity ("FM-1_906"): one marker byte after each of the first 20 blocks; null: not a package */
    fun productOf(raw: ByteArray): String? {
        if (raw.size < BLOCKS * BLK) return null
        val sb = StringBuilder()
        for (i in 0 until BLOCKS) {
            val m = raw[i * BLK + KEEP].toInt() and 0xFF
            if (m != 0x7D) sb.append(((m - i - 1) and 0xFF).toChar())
        }
        return sb.toString()
    }

    /** the image the device reads during an update: the package without the 20 marker bytes */
    fun logicalImage(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size)
        for (i in 0 until BLOCKS) out.write(raw, i * BLK, KEEP)
        out.write(raw, BLOCKS * BLK, raw.size - BLOCKS * BLK)
        return out.toByteArray()
    }

    fun modelOf(text: String) = text.substringBefore('_').removePrefix("ota-")

    fun hasLoaderMark(raw: ByteArray): Boolean {
        val m = LOADER_MARK
        outer@ for (i in 0..raw.size - m.size) {
            for (k in m.indices) if (raw[i + k] != m[k]) continue@outer
            return true
        }
        return false
    }

    fun sha256(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
