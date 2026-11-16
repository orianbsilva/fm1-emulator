// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. A firmware running on the phone: its core (Native), its flash kept between runs, and
// the MIDI that plays it: every MIDI device the phone has (a USB keyboard, a Bluetooth one Android knows, the FM-1's
// own keys) goes in, as an FM-1 over USB takes it; a controller's CC can turn the panel's knobs instead (MIDI learn).
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
import io.github.orianbsilva.obsloader.midi.MidiParser
import java.io.File
import java.util.concurrent.Executors

/** a firmware the app runs: its library, and where it comes from (GPL: its source) */
data class CoreInfo(val id: String, val name: String, val lib: String, val author: String, val version: String,
                    val source: String, val credits: String)

object Cores {
    val ALL = listOf(
        CoreInfo("obssynth-fm1", "ObsSynth FM-1", "libcore_obssynth_fm1.so", "Orian Beltrame", "0.2.0",
            "https://github.com/orianbsilva/obssynth-fm1/tree/v0.2.0",
            "On Felucca (Leo Kuroshita, Hügelton Instruments) and Hortator (DEADACTIVE). GPL-3.0."),
    )
}

/** what a CC can turn: the panel's encoders (sim_enc roles) and MASTER */
enum class Target(val label: String, val role: Int) {
    SELECT("SELECT", 0), ALGORITHM("ALGORITHM", 1), PRESETS("PRESETS", 2),
    K1("KNOB1", 3), K2("KNOB2", 4), K3("KNOB3", 5), K4("KNOB4", 6), MASTER("MASTER", -1)
}

class Emulator(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "emu").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("emu", 0)
    private val io = Executors.newSingleThreadExecutor()
    var core by mutableStateOf<CoreInfo?>(null); private set
    var failed by mutableStateOf<String?>(null); private set
    private var loaded: String? = null

    // MIDI
    private val mm = ctx.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val open = mutableMapOf<Int, Pair<MidiDevice, List<Pair<MidiOutputPort, MidiReceiver>>>>()
    val inputs = mutableStateMapOf<Int, String>()
    var learning by mutableStateOf<Target?>(null)
    val ccMap = mutableStateMapOf<Int, Target>()
    private val lastCc = IntArray(128) { -1 }
    var lastMidi by mutableStateOf("")
        private set
    var master by mutableStateOf(prefs.getInt("master", 800)); private set
    private var callback: MidiManager.DeviceCallback? = null

    private fun flashFile(c: CoreInfo) = File(dir, "${c.id}.flash")

    fun start(c: CoreInfo): Boolean {
        if (core == c) return true
        if (core != null) stop()
        if (loaded != null && loaded != c.lib) { failed = "one core per run: restart the app"; return false }
        if (!Native.load(c.lib)) { failed = "${c.name}: its core did not load"; return false }
        loaded = c.lib
        val f = flashFile(c)
        val flash = if (f.length() == FLASH) runCatching { f.readBytes() }.getOrNull() else null
        Native.start(flash)
        Native.master(master)
        core = c
        failed = null
        loadMap(c)
        startMidi()
        return true
    }

    fun stop() {
        val c = core ?: return
        saveFlash(c)
        Native.stop()
        stopMidi()
        core = null
    }

    fun pause() { core?.let { saveFlash(it) }; Native.pause() }
    fun resume() { if (core != null) Native.resume() }

    private val buf = ByteArray(FLASH.toInt())
    /** the flash the firmware wrote (its presets, its settings), kept for the next run */
    fun saveFlash(c: CoreInfo? = core) {
        if (c == null || !Native.flashIfDirty(buf)) return
        val copy = buf.copyOf()
        io.execute {
            val f = flashFile(c)
            val tmp = File(dir, "${c.id}.flash.part")
            tmp.writeBytes(copy)
            tmp.renameTo(f)
        }
    }

    /** the flash erased: the factory state (the person's presets and settings gone) */
    fun erase(c: CoreInfo) {
        val was = core == c
        if (was) stop()
        flashFile(c).delete()
        if (was) start(c)
    }

    fun turnMaster(v: Int) { master = v.coerceIn(0, 1023); Native.master(master); prefs.edit().putInt("master", master).apply() }

    // ── MIDI in ───────────────────────────────────────────────────────────────────────────────────────

    private fun loadMap(c: CoreInfo) {
        ccMap.clear()
        prefs.getString("cc.${c.id}", null)?.split(',')?.forEach { e ->
            val (cc, t) = e.split(':').takeIf { it.size == 2 } ?: return@forEach
            val n = cc.toIntOrNull() ?: return@forEach
            Target.entries.firstOrNull { it.name == t }?.let { ccMap[n] = it }
        }
    }

    private fun saveMap() {
        val c = core ?: return
        prefs.edit().putString("cc.${c.id}", ccMap.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()
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
            if (d == null || core == null) { runCatching { d?.close() }; return@openDevice }
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
        if (st >= 0xF0 || m.size < 2) return                     // SysEx, clock: not the panel's (yet)
        val d1 = m[1].toInt() and 0x7F
        val d2 = if (m.size > 2) m[2].toInt() and 0x7F else 0
        if ((st and 0xF0) == 0xB0) {
            learning?.let { t ->
                main.post { ccMap.entries.removeAll { it.value == t }; ccMap[d1] = t; learning = null; saveMap() }
                return
            }
            ccMap[d1]?.let { t -> turn(t, d1, d2); return }
        }
        Native.midi(st, d1, d2)
        if ((st and 0xF0) == 0x90 && d2 > 0) main.post { lastMidi = "note $d1" }
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

    companion object { const val FLASH = 0x100000L }
}
