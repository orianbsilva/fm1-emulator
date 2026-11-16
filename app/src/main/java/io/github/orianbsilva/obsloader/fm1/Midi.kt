// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The FM-1 over USB-MIDI with Android's MIDI API (android.media.midi): finding it,
// opening one in/out pair, SysEx out in pieces the API takes, SysEx in reassembled from the pieces it gives.
package io.github.orianbsilva.obsloader.fm1

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** whole SysEx messages (F0 .. F7) out of the byte stream; anything else is dropped, a real-time byte in the middle too */
class SysexAssembler(private val done: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream(1024)
    private var inside = false
    fun feed(b: ByteArray, off: Int, count: Int) {
        for (i in off until off + count) {
            val x = b[i].toInt() and 0xFF
            when {
                x >= 0xF8 -> Unit
                x == 0xF0 -> { buf.reset(); buf.write(x); inside = true }
                x == 0xF7 -> if (inside) { buf.write(x); inside = false; done(buf.toByteArray()) }
                x >= 0x80 -> inside = false
                inside -> { buf.write(x); if (buf.size() > 4096) inside = false }
            }
        }
    }
}

/** one MIDI in/out pair with a SysEx queue */
class MidiLink(val info: MidiDeviceInfo, private val device: MidiDevice, private val toDev: MidiInputPort,
               private val fromDev: MidiOutputPort) {
    private val q = LinkedBlockingQueue<ByteArray>()
    private val asm = SysexAssembler { q.offer(it) }
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = asm.feed(msg, offset, count)
    }
    @Volatile var lost = false

    init { fromDev.connect(receiver) }

    val name: String get() = nameOf(info)

    fun send(pkt: ByteArray): Boolean = try {
        var at = 0
        val max = 1000
        while (at < pkt.size) {          // (the API takes at most ~1 KB at once; the USB driver splits it into packets)
            val n = minOf(max, pkt.size - at)
            toDev.send(pkt, at, n)
            at += n
        }
        true
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** the next SysEx, or null after timeoutMs */
    suspend fun read(timeoutMs: Long): ByteArray? =
        withContext(Dispatchers.IO) { q.poll(maxOf(0, timeoutMs), TimeUnit.MILLISECONDS) }

    fun drain() = q.clear()

    fun close() {
        runCatching { fromDev.disconnect(receiver) }
        runCatching { fromDev.close() }
        runCatching { toDev.close() }
        runCatching { device.close() }
    }

    companion object {
        fun nameOf(info: MidiDeviceInfo): String {
            val p = info.properties
            return listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_NAME), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
                .firstOrNull { it.isNotBlank() } ?: "MIDI ${info.id}"
        }
    }
}

/** the USB MIDI devices that may be an FM-1, and opening them */
class Fm1Usb(context: Context) {
    private val mm = context.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val removed = mutableSetOf<Int>()
    private val open = mutableMapOf<Int, MidiLink>()

    val available: Boolean get() = mm != null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            synchronized(open) { open[info.id]?.lost = true; removed += info.id }
        }
        override fun onDeviceAdded(info: MidiDeviceInfo) { synchronized(open) { removed -= info.id } }
    }

    init {
        if (mm != null) {
            if (Build.VERSION.SDK_INT >= 33) mm.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, exec, callback)
            else @Suppress("DEPRECATION") mm.registerDeviceCallback(callback, main)
        }
    }

    fun devices(): List<MidiDeviceInfo> {
        val m = mm ?: return emptyList()
        val all = if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
                  else @Suppress("DEPRECATION") m.devices.toList()
        return all.filter { it.inputPortCount > 0 && it.outputPortCount > 0 }
    }

    /** the devices whose name says FM-1 (or a firmware on it, or its loader): only those are probed by themselves */
    fun candidates(): List<MidiDeviceInfo> =
        devices().filter { it.type == MidiDeviceInfo.TYPE_USB && NAME.containsMatchIn(MidiLink.nameOf(it)) }

    suspend fun open(info: MidiDeviceInfo): MidiLink? {
        val m = mm ?: return null
        val dev: MidiDevice = suspendCancellableCoroutine { c -> m.openDevice(info, { d -> c.resume(d) }, main) } ?: return null
        val inIdx = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.portNumber ?: 0
        val outIdx = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.portNumber ?: 0
        val i = dev.openInputPort(inIdx)
        val o = dev.openOutputPort(outIdx)
        if (i == null || o == null) {
            runCatching { i?.close() }; runCatching { o?.close() }; runCatching { dev.close() }
            return null
        }
        val link = MidiLink(info, dev, i, o)
        synchronized(open) { open[info.id] = link; if (info.id in removed) link.lost = true }
        kotlinx.coroutines.delay(300)   // (a message sent just after opening may be dropped)
        return link
    }

    companion object {
        // the app's USB names (Hortator, Felucca, ObsSynthFM1 and the others), M-VAVE's, and the update loader's
        val NAME = Regex("fm-?1|hortator|felucca|ota|composite|sinco|usb-midi|obssynth|m-vave|jieli", RegexOption.IGNORE_CASE)
    }
}
