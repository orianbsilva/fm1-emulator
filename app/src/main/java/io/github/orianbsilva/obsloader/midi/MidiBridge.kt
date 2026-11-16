// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. Web MIDI for the pages the app opens (the simulators, the makers' browser versions and
// editors): Android's WebView has no navigator.requestMIDIAccess, so a script given at document start (midi_bridge.js)
// makes one, and this answers it over a WebMessageListener: the phone's MIDI devices (a USB keyboard through a hub, the
// FM-1, a Bluetooth one Android already knows) as inputs and outputs. Only the page's own origin gets it.
package io.github.orianbsilva.obsloader.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.orianbsilva.obsloader.fm1.MidiLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MidiBridge(private val ctx: Context) {
    private val mm = ctx.getSystemService(MidiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val devices = mutableMapOf<Int, MidiDevice>()            // opened, by MidiDeviceInfo id
    private val inputs = mutableMapOf<String, Pair<MidiOutputPort, MidiReceiver>>()   // a page's input: a device's output port
    private val outputs = mutableMapOf<String, MidiInputPort>()                       // a page's output: a device's input port
    private var proxy: JavaScriptReplyProxy? = null
    private var callback: MidiManager.DeviceCallback? = null
    private val exec = Executors.newSingleThreadExecutor()

    /** the WebView gets navigator.requestMIDIAccess for these origins (scheme://host) */
    fun attach(web: WebView, origins: Set<String>): Boolean {
        if (mm == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        val script = ctx.assets.open("midi_bridge.js").use { it.readBytes().toString(Charsets.UTF_8) }
        WebViewCompat.addWebMessageListener(web, "obsMidi", origins) { _, msg, _, _, reply -> onMessage(msg, reply) }
        WebViewCompat.addDocumentStartJavaScript(web, script, origins)
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = main.post { sendList() }.let { }
            override fun onDeviceRemoved(info: MidiDeviceInfo) = main.post { forget(info.id); sendList() }.let { }
        }
        if (Build.VERSION.SDK_INT >= 33) mm.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, exec, cb)
        else @Suppress("DEPRECATION") mm.registerDeviceCallback(cb, main)
        callback = cb
        return true
    }

    fun close() {
        callback?.let { runCatching { mm?.unregisterDeviceCallback(it) } }
        callback = null
        for ((p, r) in inputs.values) { runCatching { p.disconnect(r) }; runCatching { p.close() } }
        inputs.clear()
        outputs.values.forEach { runCatching { it.close() } }
        outputs.clear()
        devices.values.forEach { runCatching { it.close() } }
        devices.clear()
        proxy = null
    }

    private fun infos(): List<MidiDeviceInfo> {
        val m = mm ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
               else @Suppress("DEPRECATION") m.devices.toList()
    }

    private fun portName(info: MidiDeviceInfo, p: MidiDeviceInfo.PortInfo, count: Int): String {
        val base = MidiLink.nameOf(info)
        return if (count > 1) base + " " + (p.name.ifBlank { (p.portNumber + 1).toString() }) else base
    }

    private fun list(): JSONObject {
        val ins = JSONArray()
        val outs = JSONArray()
        for (info in infos()) {
            val maker = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
            for (p in info.ports) {
                // a device's OUTPUT port is a page's input (what it plays), its INPUT port a page's output
                if (p.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT)
                    ins.put(JSONObject().put("id", "i${info.id}.${p.portNumber}").put("name", portName(info, p, info.outputPortCount)).put("manufacturer", maker))
                else
                    outs.put(JSONObject().put("id", "o${info.id}.${p.portNumber}").put("name", portName(info, p, info.inputPortCount)).put("manufacturer", maker))
            }
        }
        return JSONObject().put("t", "list").put("inputs", ins).put("outputs", outs)
    }

    private fun post(j: JSONObject) = main.post { runCatching { proxy?.postMessage(j.toString()) } }

    private fun sendList() = post(list())

    private fun forget(devId: Int) {
        inputs.keys.filter { it.startsWith("i$devId.") }.forEach { k -> inputs.remove(k)?.let { (p, r) -> runCatching { p.disconnect(r) }; runCatching { p.close() } } }
        outputs.keys.filter { it.startsWith("o$devId.") }.forEach { k -> outputs.remove(k)?.let { runCatching { it.close() } } }
        devices.remove(devId)?.let { runCatching { it.close() } }
    }

    private fun withDevice(devId: Int, then: (MidiDevice) -> Unit) {
        devices[devId]?.let { return then(it) }
        val info = infos().firstOrNull { it.id == devId } ?: return
        mm?.openDevice(info, { d -> if (d != null) { devices[devId] = d; then(d) } }, main)
    }

    private fun ids(id: String): Pair<Int, Int>? {
        val (a, b) = id.drop(1).split('.').let { if (it.size == 2) it else return null }
        return (a.toIntOrNull() ?: return null) to (b.toIntOrNull() ?: return null)
    }

    private fun onMessage(msg: WebMessageCompat, reply: JavaScriptReplyProxy) {
        proxy = reply
        val j = runCatching { JSONObject(msg.data ?: return) }.getOrNull() ?: return
        when (j.optString("t")) {
            "list" -> sendList()
            "open" -> {                                   // a page listens to an input
                val id = j.optString("id")
                if (id in inputs) return
                val (dev, port) = ids(id) ?: return
                withDevice(dev) { d ->
                    if (id in inputs) return@withDevice
                    val p = d.openOutputPort(port) ?: return@withDevice
                    val parser = MidiParser { m ->
                        val arr = JSONArray(); for (x in m) arr.put(x.toInt() and 0xFF)
                        post(JSONObject().put("t", "msg").put("id", id).put("data", arr))
                    }
                    val r = object : MidiReceiver() {
                        override fun onSend(b: ByteArray, off: Int, n: Int, ts: Long) = parser.feed(b, off, n)
                    }
                    p.connect(r)
                    inputs[id] = p to r
                }
            }
            "send" -> {                                   // a page plays an output
                val id = j.optString("id")
                val data = j.optJSONArray("data") ?: return
                val b = ByteArray(data.length()) { data.optInt(it).toByte() }
                val ready = outputs[id]
                if (ready != null) { sendTo(ready, b); return }
                val (dev, port) = ids(id) ?: return
                withDevice(dev) { d ->
                    val p = outputs[id] ?: d.openInputPort(port)?.also { outputs[id] = it } ?: return@withDevice
                    sendTo(p, b)
                }
            }
        }
    }

    private fun sendTo(p: MidiInputPort, b: ByteArray) {
        var at = 0
        while (at < b.size) { val n = minOf(1000, b.size - at); runCatching { p.send(b, at, n) }; at += n }
    }

    companion object {
        /** the origin a page's MIDI is given to: its own scheme://host[:port] */
        fun originOf(url: String): String? = Uri.parse(url).let { u ->
            val h = u.host ?: return null
            u.scheme + "://" + h + if (u.port > 0) ":" + u.port else ""
        }
    }
}
