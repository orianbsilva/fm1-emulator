// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. A MIDI keyboard plays the FM-1 through the phone: what the keyboard sends (notes,
// the wheels, the controllers, the clock; never SysEx) goes to the FM-1's USB-MIDI in. Both on USB through a hub, or
// the keyboard on Bluetooth.
package io.github.orianbsilva.obsloader.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Thru(ctx: Context) {
    private val mm = ctx.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var from: MidiDevice? = null
    private var to: MidiDevice? = null
    private var src: MidiOutputPort? = null
    private var dst: MidiInputPort? = null
    private var recv: MidiReceiver? = null
    @Volatile var count = 0L; private set

    val running get() = dst != null

    private suspend fun open(info: MidiDeviceInfo): MidiDevice? =
        suspendCancellableCoroutine { c -> mm?.openDevice(info, { d -> c.resume(d) }, main) ?: c.resume(null) }

    /** keyboard: a device with an output port; fm1: the FM-1 (an input port); false if either would not open */
    suspend fun start(keyboard: MidiDeviceInfo, fm1: MidiDeviceInfo): Boolean {
        stop()
        val a = open(keyboard) ?: return false
        val b = open(fm1) ?: run { a.close(); return false }
        val outPort = keyboard.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.portNumber ?: 0
        val inPort = fm1.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.portNumber ?: 0
        val s = a.openOutputPort(outPort)
        val d = b.openInputPort(inPort)
        if (s == null || d == null) { runCatching { s?.close() }; runCatching { d?.close() }; a.close(); b.close(); return false }
        val parser = MidiParser { m ->
            if ((m[0].toInt() and 0xFF) != 0xF0) { runCatching { d.send(m, 0, m.size) }; count++ }
        }
        val r = object : MidiReceiver() { override fun onSend(x: ByteArray, off: Int, n: Int, ts: Long) = parser.feed(x, off, n) }
        s.connect(r)
        from = a; to = b; src = s; dst = d; recv = r; count = 0
        return true
    }

    fun stop() {
        val d = dst
        if (d != null) {   // the notes that sound stop: all notes off on every channel
            for (ch in 0 until 16) runCatching { d.send(byteArrayOf((0xB0 or ch).toByte(), 123, 0), 0, 3) }
        }
        recv?.let { r -> runCatching { src?.disconnect(r) } }
        runCatching { src?.close() }; runCatching { dst?.close() }
        runCatching { from?.close() }; runCatching { to?.close() }
        from = null; to = null; src = null; dst = null; recv = null
    }
}
