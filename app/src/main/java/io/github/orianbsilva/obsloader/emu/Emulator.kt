// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. A firmware package running on the emulated FM-1 (Native: fm1-emu), its flash kept
// between runs (each exact package its own), and the MIDI that plays it: every MIDI device the phone has (a USB
// keyboard, a Bluetooth one Android knows, an FM-1's own keys) goes into the MIDI IN jack, as a cable would; a
// controller's CC can turn the panel's knobs instead (MIDI learn).
package io.github.orianbsilva.obsloader.emu

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.orianbsilva.obsloader.fm1.MidiLink
import io.github.orianbsilva.obsloader.fm1.Wire
import io.github.orianbsilva.obsloader.midi.MidiParser
import java.io.File
import java.util.concurrent.Executors

/** a package the emulator runs: what it is called and its bytes' place */
data class EmuPackage(val id: String, val name: String, val version: String, val author: String, val load: () -> ByteArray)

/** what a CC can turn: the panel's encoders (fm1-emu's encoder ids) and MASTER */
enum class Target(val label: String, val role: Int) {
    SELECT("SELECT", 0), ALGORITHM("ALGORITHM", 1), PRESETS("PRESETS", 6),
    K1("KNOB1", 2), K2("KNOB2", 3), K3("KNOB3", 4), K4("KNOB4", 5), MASTER("MASTER", -1)
}

class Emulator(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "emu").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("emu", 0)
    private val io = Executors.newSingleThreadExecutor()
    var running by mutableStateOf<EmuPackage?>(null); private set
    var failed by mutableStateOf<String?>(null); private set
    private var saveKey: String? = null
    private var savedRev = 0
    private var revAt = 0L

    // MIDI
    private val mm = ctx.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val open = mutableMapOf<Int, Pair<MidiDevice, List<Pair<MidiOutputPort, MidiReceiver>>>>()
    val inputs = mutableStateMapOf<Int, String>()
    var learning by mutableStateOf<Target?>(null)
    val ccMap = mutableStateMapOf<Int, Target>()
    private val lastCc = IntArray(128) { -1 }
    var master by mutableStateOf(prefs.getInt("master", 800)); private set
    /** the on-screen keys (off: a MIDI keyboard plays, the panel takes the room) */
    var keysShown: Boolean
        get() = prefs.getBoolean("keys", true)
        set(v) { prefs.edit().putBoolean("keys", v).apply() }
    private var callback: MidiManager.DeviceCallback? = null

    /** each exact package (its sha256) keeps its own flash: a firmware's saves are only its own version's */
    private fun saveFile(key: String) = File(dir, "$key.sectors")

    /** boot it (its saved flash too), with the FM-1's flash part (newer: the UC25HQ80) */
    fun start(p: EmuPackage, newerChip: Boolean): Boolean {
        if (running?.id == p.id) return true
        stop()
        if (!Native.load()) { failed = "the emulator's library is missing"; return false }
        val bytes = runCatching { p.load() }.getOrElse { failed = "${p.name}: ${it.message}"; return false }
        val key = Wire.sha256(bytes).take(16) + if (newerChip) "-uc25" else ""
        val saved = saveFile(key).takeIf { it.exists() }?.readBytes()
        when (Native.boot(bytes, saved, if (newerChip) 1 else 0)) {
            0 -> Unit
            2 -> { saveFile(key).delete(); failed = "${p.name}: its saved memory did not fit and was dropped" }
            3 -> { Native.stop(); failed = "${p.name} is for the SMK-37"; return false }
            else -> { failed = "${p.name}: not an FM-1 package"; return false }
        }
        Native.master(master)
        saveKey = key
        savedRev = Native.flashRevision(); revAt = 0
        running = p
        if (failed?.startsWith(p.name) != true) failed = null
        loadMap()
        startMidi()
        return true
    }

    fun stop() {
        if (running == null) return
        saveFlash(force = true)
        Native.stop()
        stopMidi()
        running = null
    }

    fun pause() { saveFlash(force = true); Native.pause() }
    fun resume() { if (running != null) Native.resume() }

    /** the flash the firmware wrote (its presets, its settings), kept for its next run: once it has been quiet a
     *  moment (a save in progress is not cut in half) */
    fun saveFlash(force: Boolean = false) {
        val key = saveKey ?: return
        val rev = Native.flashRevision()
        if (rev == savedRev) return
        val now = System.currentTimeMillis()
        if (!force) {
            if (revAt == 0L || rev != lastSeen) { lastSeen = rev; revAt = now; return }
            if (now - revAt < 400) return
        }
        val sectors = Native.sectors() ?: return
        savedRev = rev; revAt = 0
        io.execute {
            val tmp = File(dir, "$key.part")
            tmp.writeBytes(sectors)
            tmp.renameTo(saveFile(key))
        }
    }
    private var lastSeen = 0

    /** this package's memory erased: the factory state */
    fun erase(p: EmuPackage, newerChip: Boolean) {
        val key = saveKey
        stop()
        if (key != null) saveFile(key).delete()
        start(p, newerChip)
    }

    fun turnMaster(v: Int) { master = v.coerceIn(0, 1023); Native.master(master); prefs.edit().putInt("master", master).apply() }

    // ── MIDI in ───────────────────────────────────────────────────────────────────────────────────────

    private fun loadMap() {
        ccMap.clear()
        prefs.getString("cc", null)?.split(',')?.forEach { e ->
            val (cc, t) = e.split(':').takeIf { it.size == 2 } ?: return@forEach
            val n = cc.toIntOrNull() ?: return@forEach
            Target.entries.firstOrNull { it.name == t }?.let { ccMap[n] = it }
        }
    }

    private fun saveMap() {
        prefs.edit().putString("cc", ccMap.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()
    }

    fun unmap(cc: Int) { ccMap.remove(cc); saveMap() }

    private fun infos(): List<MidiDeviceInfo> {
        val m = mm ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
               else @Suppress("DEPRECATION") m.devices.toList()
    }

    private fun startMidi() {
        val m = mm ?: return
        infos().forEach { openDevice(it) }
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) { main.post { openDevice(info) } }
            override fun onDeviceRemoved(info: MidiDeviceInfo) { main.post { closeDevice(info.id) } }
        }
        if (Build.VERSION.SDK_INT >= 33) m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, io, cb)
        else @Suppress("DEPRECATION") m.registerDeviceCallback(cb, main)
        callback = cb
    }

    private fun stopMidi() {
        callback?.let { runCatching { mm?.unregisterDeviceCallback(it) } }
        callback = null
        open.keys.toList().forEach { closeDevice(it) }
    }

    private fun openDevice(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || info.id in open) return
        mm?.openDevice(info, { d ->
            if (d == null || running == null) { runCatching { d?.close() }; return@openDevice }
            val ports = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.mapNotNull { p ->
                val port = d.openOutputPort(p.portNumber) ?: return@mapNotNull null
                val parser = MidiParser { msg -> onMidi(msg) }
                val r = object : MidiReceiver() { override fun onSend(b: ByteArray, o: Int, n: Int, ts: Long) = parser.feed(b, o, n) }
                port.connect(r)
                port to r
            }
            open[info.id] = d to ports
            inputs[info.id] = MidiLink.nameOf(info)
        }, main)
    }

    private fun closeDevice(id: Int) {
        open.remove(id)?.let { (d, ports) ->
            ports.forEach { (p, r) -> runCatching { p.disconnect(r) }; runCatching { p.close() } }
            runCatching { d.close() }
        }
        inputs.remove(id)
    }

    /** a whole message from a MIDI input (any thread) */
    private fun onMidi(m: ByteArray) {
        val st = m[0].toInt() and 0xFF
        if (st >= 0xF0 || m.size < 2) { Native.midi(m); return }   // SysEx, clock: the firmware's, as from the jack
        val d1 = m[1].toInt() and 0x7F
        val d2 = if (m.size > 2) m[2].toInt() and 0x7F else 0
        if ((st and 0xF0) == 0xB0) {
            learning?.let { t ->
                main.post { ccMap.entries.removeAll { it.value == t }; ccMap[d1] = t; learning = null; saveMap() }
                return
            }
            ccMap[d1]?.let { t -> turn(t, d1, d2); return }
        }
        Native.midi(m)
    }

    /** a mapped CC turns its knob: MASTER takes the value; an encoder the change since the last value (at an end,
     *  the same value again keeps turning it, as a relative controller does) */
    private fun turn(t: Target, cc: Int, v: Int) {
        if (t == Target.MASTER) { val x = v * 1023 / 127; main.post { turnMaster(x) }; return }
        val last = lastCc[cc]
        lastCc[cc] = v
        val steps = when {
            last < 0 -> 0
            v != last -> v - last
            v == 127 -> 1
            v == 0 -> -1
            else -> 0
        }
        if (steps != 0) Native.enc(t.role, steps.coerceIn(-16, 16))
    }

}
