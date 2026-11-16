// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. Installing a package on an FM-1, as Felucca's installer does it (fm1_install.py):
// step 1, the running firmware reads parts of the package and starts the update loader; step 2, the loader reads the
// whole package and writes it; then the FM-1 restarts and its identity is checked. In both steps the device asks
// (read requests on the logical image) and we answer. An FM-1 already in update mode only needs step 2.
package io.github.orianbsilva.obsloader.fm1

import kotlinx.coroutines.delay

class InstallError(val code: Code, msg: String) : Exception(msg) {
    enum class Code { NOT_FOUND, MODEL, LOST, STOPPED, BAD_REQUEST, NO_LOADER, NO_RETURN, MISMATCH, BAD_PACKAGE }
}

sealed interface Step {
    data class Start(val on: String) : Step
    data class Check(val reads: Int) : Step
    data object Loader : Step
    data class Write(val percent: Int) : Step
    data object Reboot : Step
    data class Done(val ident: String) : Step
}

class Device(val link: MidiLink, val id: Identity)

class Updater(private val usb: Fm1Usb) {
    // milliseconds (fm1_install.py's DELAY)
    private val tStart = 2000L; private val tReply = 10L; private val tLoader = 3000L; private val tReboot = 3000L
    private val tRetry = 1000L; private val tPoll = 1000L; private val tHs = 1000L
    private val idleCheck = 8000L; private val idleWrite = 180_000L
    private val waitLoader = 30_000L; private val waitReboot = 40_000L

    suspend fun handshake(link: MidiLink, tries: Int = 3): Identity? {
        link.drain()
        repeat(tries) {
            if (!link.send(Wire.HS_QUERY)) return null
            val end = System.currentTimeMillis() + tHs
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                val p = link.read(left) ?: break
                Wire.parseIdentity(p)?.let { return it }
            }
        }
        return null
    }

    /** the first FM-1 that answers (and want(identity)); only = that device only */
    suspend fun find(want: (Identity) -> Boolean = { true }, only: android.media.midi.MidiDeviceInfo? = null): Device? {
        val list = if (only != null) listOf(only) else usb.candidates()
        for (info in list) {
            val link = usb.open(info) ?: continue
            val id = handshake(link, 2)
            if (id != null && want(id)) return Device(link, id)
            link.close()
        }
        return null
    }

    private suspend fun waitFor(want: (Identity) -> Boolean, ms: Long): Device? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find(want)?.let { return it }
            delay(tRetry)
        }
        return null
    }

    private data class Served(val count: Int, val finished: Boolean, val lost: Boolean)

    private suspend fun serve(link: MidiLink, image: ByteArray, finish: Long, idle: Long, progress: (Int, Long) -> Unit): Served {
        var served = 0
        var last = System.currentTimeMillis()
        while (true) {
            val pkt = link.read(tPoll)
            if (pkt == null) {
                if (link.lost) return Served(served, false, true)
                if (System.currentTimeMillis() - last > idle) return Served(served, false, false)
                continue
            }
            val r = Wire.parseRequest(pkt) ?: continue
            last = System.currentTimeMillis()
            if (r.addr == Wire.FINISH_CHECK || r.addr == Wire.FINISH_WRITE) {
                link.send(Wire.response(r.addr, "success\u0000".toByteArray(Charsets.US_ASCII)))
                if (r.addr == finish) return Served(served, true, false)
                continue
            }
            if (r.len > Wire.MAXDATA || r.addr + r.len > image.size)
                throw InstallError(InstallError.Code.BAD_REQUEST, "the device asked for 0x%x+%d, outside the package".format(r.addr, r.len))
            delay(tReply)
            val a = r.addr.toInt()
            if (!link.send(Wire.response(r.addr, image, a, a + r.len, r.flags))) return Served(served, false, true)
            served++
            progress(served, r.addr + r.len)
        }
    }

    private suspend fun check(dev: Device, image: ByteArray, step: (Step) -> Unit): Device {
        step(Step.Start(dev.id.text))
        dev.link.send(Wire.UPGRADE)
        delay(tStart)
        val s = serve(dev.link, image, Wire.FINISH_CHECK, idleCheck) { k, _ -> step(Step.Check(k)) }
        dev.link.close()
        if (!s.finished) throw InstallError(if (s.lost) InstallError.Code.LOST else InstallError.Code.STOPPED,
            "the FM-1 ${if (s.lost) "was disconnected" else "stopped answering"} after ${s.count} requests; nothing was written")
        step(Step.Loader)
        delay(tLoader)
        return waitFor({ it.loader }, waitLoader) ?: throw InstallError(InstallError.Code.NO_LOADER,
            "the update loader did not appear. Replug the USB cable and install again: the FM-1 stays in update mode until it is done")
    }

    private suspend fun write(ota: Device, image: ByteArray, step: (Step) -> Unit) {
        step(Step.Write(0))
        ota.link.send(Wire.UPGRADE)
        delay(tStart)
        val s = serve(ota.link, image, Wire.FINISH_WRITE, idleWrite) { _, end -> step(Step.Write(minOf(99, (end * 100 / image.size).toInt()))) }
        ota.link.close()
        if (!s.finished) throw InstallError(if (s.lost) InstallError.Code.LOST else InstallError.Code.STOPPED,
            "the loader ${if (s.lost) "was disconnected" else "stopped answering"} after ${s.count} requests. Replug the USB cable and install again to finish")
        step(Step.Write(100))
    }

    private suspend fun verify(product: String, step: (Step) -> Unit): String {
        step(Step.Reboot)
        delay(tReboot)
        val back = waitFor({ !it.loader }, waitReboot) ?: throw InstallError(InstallError.Code.NO_RETURN, "the FM-1 did not come back: switch it off and on")
        back.link.close()
        if (back.id.text != product) throw InstallError(InstallError.Code.MISMATCH, "written, but the FM-1 reports ${back.id.text} (expected $product)")
        step(Step.Done(back.id.text))
        return back.id.text
    }

    /** running firmware -> loader -> new firmware; dev from find() */
    suspend fun install(dev: Device, image: ByteArray, product: String, step: (Step) -> Unit): String {
        if (Wire.modelOf(dev.id.text) != Wire.modelOf(product)) {
            dev.link.close()
            throw InstallError(InstallError.Code.MODEL, "the device is ${dev.id.text}, the package is for $product")
        }
        val ota = if (dev.id.loader) dev else check(dev, image, step)
        write(ota, image, step)
        return verify(product, step)
    }
}
