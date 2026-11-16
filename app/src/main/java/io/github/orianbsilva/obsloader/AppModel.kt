// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. The app's state: the hub's list, the packages on the phone, the FM-1 on USB and an
// install in progress. One ViewModel for the whole app (a few screens over the same data).
package io.github.orianbsilva.obsloader

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.orianbsilva.obsloader.fm1.Fm1Usb
import io.github.orianbsilva.obsloader.fm1.Identity
import io.github.orianbsilva.obsloader.fm1.InstallError
import io.github.orianbsilva.obsloader.fm1.Step
import io.github.orianbsilva.obsloader.fm1.Updater
import io.github.orianbsilva.obsloader.fm1.Wire
import io.github.orianbsilva.obsloader.hub.Catalog
import io.github.orianbsilva.obsloader.hub.CatalogStore
import io.github.orianbsilva.obsloader.hub.Firmware
import io.github.orianbsilva.obsloader.hub.PackageStore
import io.github.orianbsilva.obsloader.hub.PictureStore
import io.github.orianbsilva.obsloader.hub.RateLimited
import io.github.orianbsilva.obsloader.midi.Thru
import android.media.midi.MidiDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** the FM-1's flash chip, as the person says it is (the hub cannot see it): the newer one (0xB36014) breaks
 *  firmwares that know only the first one */
enum class Chip { UNKNOWN, FIRST, NEWER }

/** a package about to be installed, waiting for the person's yes */
data class Pending(val label: String, val product: String, val raw: ByteArray, val device: Identity, val deviceName: String,
                   val warnings: List<String>)

class AppModel(app: Application) : AndroidViewModel(app) {
    private val files = app.filesDir
    private val prefs = app.getSharedPreferences("obsloader", 0)
    val catalogStore = CatalogStore(files)
    val packages = PackageStore(files)
    val pictures = PictureStore(app.cacheDir)
    val repos = io.github.orianbsilva.obsloader.hub.RepoStore(prefs, packages)
    /** the person's own repositories (off the hub) on the phone */
    val repoList = mutableStateListOf<String>()
    val usb = Fm1Usb(app)
    val emulator = io.github.orianbsilva.obsloader.emu.Emulator(app)
    private val updater = Updater(usb)

    var catalog by mutableStateOf<Catalog?>(null); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    /** ids of the firmwares on the phone, and their sha256 (to tell a current one from an old one) */
    val onPhone = mutableStateMapOf<String, String>()
    val downloading = mutableStateMapOf<String, Float>()
    var bulk by mutableStateOf<String?>(null); private set
    private var bulkJob: Job? = null

    var chip by mutableStateOf(Chip.entries[prefs.getInt("chip", 0)]); private set
    fun chooseChip(c: Chip) { chip = c; prefs.edit().putInt("chip", c.ordinal).apply() }

    // the FM-1
    var device by mutableStateOf<Identity?>(null); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var probing by mutableStateOf(false); private set
    var pending by mutableStateOf<Pending?>(null); private set
    var installing by mutableStateOf(false); private set
    var step by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set
    val log = mutableStateListOf<String>()
    var message by mutableStateOf<String?>(null)

    // a MIDI keyboard playing the FM-1 through the phone
    private val thru = Thru(app)
    var thruFrom by mutableStateOf<String?>(null); private set
    var keyboards by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set

    /** the MIDI devices that are not an FM-1 (keyboards, controllers, Bluetooth ones Android knows) */
    fun refreshKeyboards() {
        val fm = usb.candidates().map { it.id }.toSet()
        keyboards = usb.devices().filter { it.id !in fm && it.outputPortCount > 0 }.map { it.id to io.github.orianbsilva.obsloader.fm1.MidiLink.nameOf(it) }
    }

    fun startThru(keyboardId: Int) {
        if (installing || probing) return
        viewModelScope.launch {
            val kb = usb.devices().firstOrNull { it.id == keyboardId }
            val fm = usb.candidates().firstOrNull()
            when {
                kb == null -> message = s(R.string.thru_gone)
                fm == null -> message = notFound()
                thru.start(kb, fm) -> thruFrom = io.github.orianbsilva.obsloader.fm1.MidiLink.nameOf(kb) + " → " + io.github.orianbsilva.obsloader.fm1.MidiLink.nameOf(fm)
                else -> message = s(R.string.thru_failed)
            }
        }
    }

    fun stopThru() { thru.stop(); thruFrom = null }

    override fun onCleared() { thru.stop(); emulator.stop() }

    // ── the emulator: every package on the phone plays, offline ─────────────────────────────────────────────

    var playing by mutableStateOf<io.github.orianbsilva.obsloader.emu.EmuPackage?>(null)
    private val opened = java.io.File(app.filesDir, "emu/files").apply { mkdirs() }

    /** ObsSynth FM-1, in the app (it plays with no download) */
    val builtIn = io.github.orianbsilva.obsloader.emu.EmuPackage("builtin:obssynth-fm1", "ObsSynth FM-1", "0.2.0", "Orian Beltrame") {
        app.assets.open("fw/obssynth-fm1-0.2.0.fwsc").use { it.readBytes() }
    }

    fun emuOf(f: Firmware) = io.github.orianbsilva.obsloader.emu.EmuPackage("hub:" + f.id, f.name, f.version, f.author) {
        packages.stored(f.id)!!.file.readBytes()
    }

    /** what plays without the internet: the built-in one, the list's packages on the phone, the V15, opened files */
    fun playable(): List<io.github.orianbsilva.obsloader.emu.EmuPackage> {
        val list = mutableListOf<io.github.orianbsilva.obsloader.emu.EmuPackage>()
        catalog?.firmwares?.filter { onPhone[it.id] == it.sha256 }?.forEach { list += emuOf(it) }
        for (repo in repoList) packages.stored(io.github.orianbsilva.obsloader.hub.RepoStore.idOf(repo))?.let { st ->
            list += io.github.orianbsilva.obsloader.emu.EmuPackage("repo:$repo", repo.substringAfter('/'), st.version, repo.substringBefore('/')) { st.file.readBytes() }
        }
        packages.stored(PackageStore.STOCK_ID)?.let { st ->
            list += io.github.orianbsilva.obsloader.emu.EmuPackage("stock:v15", "M-VAVE FM-1", "V15", "M-VAVE") { st.file.readBytes() }
        }
        opened.listFiles { x -> x.name.endsWith(".fwsc") }.orEmpty().sortedBy { it.name }.forEach { file ->
            list += io.github.orianbsilva.obsloader.emu.EmuPackage("file:" + file.name, file.name.substringAfter('-').removeSuffix(".fwsc"), "", "") { file.readBytes() }
        }
        list += builtIn
        return list
    }

    /** a firmware of the list in the emulator: from the phone, or downloaded first (then it stays) */
    fun play(f: Firmware) {
        if (onPhone[f.id] == f.sha256) { playing = emuOf(f); return }
        if (!f.fromRelease) { message = s(R.string.err_download, f.name, s(R.string.maker_page_only)); return }
        downloading[f.id] = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { packages.download(f) { got, total -> if (total > 0) downloading[f.id] = got.toFloat() / total } }
                refreshStored()
                playing = emuOf(f)
            } catch (e: RateLimited) {
                message = s(R.string.err_rate, time(e.resetAt))
            } catch (e: Exception) {
                message = s(R.string.err_download, f.name, e.message ?: e.toString())
            } finally {
                downloading.remove(f.id)
            }
        }
    }

    /** a .fwsc from the phone: kept beside the others (it plays offline next time too) */
    fun playFile(uri: Uri) {
        val raw = runCatching { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        if (raw == null || Wire.productOf(raw) == null) { message = s(R.string.err_notpkg); return }
        val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "package").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = java.io.File(opened, Wire.sha256(raw).take(8) + "-" + (if (name.endsWith(".fwsc")) name else "$name.fwsc"))
        file.writeBytes(raw)
        playing = io.github.orianbsilva.obsloader.emu.EmuPackage("file:" + file.name, file.name.substringAfter('-').removeSuffix(".fwsc"), "", "") { file.readBytes() }
    }

    fun forgetFile(p: io.github.orianbsilva.obsloader.emu.EmuPackage) {
        if (p.id.startsWith("file:")) java.io.File(opened, p.id.removePrefix("file:")).delete()
        refreshStored()
    }

    /** when each firmware was last installed from the phone (for the library's order) */
    fun installedAt(id: String) = prefs.getLong("at.$id", 0L)

    private val res get() = getApplication<Application>().resources
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)

    init {
        catalog = catalogStore.cached()
        refreshStored()
        repoList.addAll(repos.list())
        loadCatalog()
        checkRepos(false)
    }

    // ── repositories off the hub ───────────────────────────────────────────────────────────────────────

    fun addRepo(text: String) {
        val repo = io.github.orianbsilva.obsloader.hub.RepoStore.parse(text) ?: run { message = s(R.string.repo_bad); return }
        val id = io.github.orianbsilva.obsloader.hub.RepoStore.idOf(repo)
        downloading[id] = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repos.fetch(repo) }
                if (repo !in repoList) repoList += repo
                refreshStored()
                message = s(R.string.repo_added, repo)
            } catch (e: RateLimited) {
                message = s(R.string.err_rate, time(e.resetAt))
            } catch (e: Exception) {
                message = s(R.string.err_download, repo, e.message ?: e.toString())
            } finally {
                downloading.remove(id)
            }
        }
    }

    fun removeRepo(repo: String) { repos.remove(repo); repoList.remove(repo); refreshStored() }

    /** new releases of the person's repositories: at most once a day by itself, or when asked */
    fun checkRepos(now: Boolean) {
        val last = prefs.getLong("repos.checked", 0)
        if (!now && System.currentTimeMillis() - last < CatalogStore.DAY) return
        prefs.edit().putLong("repos.checked", System.currentTimeMillis()).apply()
        viewModelScope.launch {
            var newer = 0
            for (repo in repoList.toList()) runCatching {
                val r = withContext(Dispatchers.IO) { repos.latest(repo) }
                if (packages.stored(io.github.orianbsilva.obsloader.hub.RepoStore.idOf(repo))?.version != r.tag) {
                    withContext(Dispatchers.IO) { repos.fetch(repo) }
                    newer++
                }
            }
            refreshStored()
            if (now) message = s(R.string.repo_checked, newer)
        }
    }

    private fun refreshStored() {
        onPhone.clear()
        packages.all().forEach { onPhone[it.id] = it.sha256 }
    }

    /** the list: from the phone, and from the hub if a day has passed */
    fun loadCatalog() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { catalogStore.load() }
                catalog = c
                catalogError = null
                if (c != null) withContext(Dispatchers.IO) { packages.prune(c) }
                refreshStored()
            } catch (e: Exception) {
                catalogError = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun nextFetch() = catalogStore.lastFetch() + CatalogStore.DAY

    fun download(f: Firmware) {
        if (f.id in downloading) return
        downloading[f.id] = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { packages.download(f) { got, total -> if (total > 0) downloading[f.id] = got.toFloat() / total } }
                refreshStored()
            } catch (e: RateLimited) {
                message = s(R.string.err_rate, time(e.resetAt))
            } catch (e: Exception) {
                message = s(R.string.err_download, f.name, e.message ?: e.toString())
            } finally {
                downloading.remove(f.id)
            }
        }
    }

    fun forget(id: String) { packages.delete(id); refreshStored() }

    /** every firmware that comes from a GitHub release, one after another; at GitHub's limit, it waits */
    fun downloadAll() {
        val c = catalog ?: return
        if (bulkJob?.isActive == true) { bulkJob?.cancel(); bulk = null; return }
        bulkJob = viewModelScope.launch {
            val todo = c.firmwares.filter { it.fromRelease && onPhone[it.id] != it.sha256 }
            var done = 0
            var failed = 0
            for (f in todo) {
                if (!isActive) break
                bulk = s(R.string.bulk_progress, done, todo.size)
                downloading[f.id] = 0f
                while (isActive) {
                    try {
                        withContext(Dispatchers.IO) { packages.download(f) { got, total -> if (total > 0) downloading[f.id] = got.toFloat() / total } }
                        break
                    } catch (e: RateLimited) {
                        bulk = s(R.string.bulk_waiting, done, todo.size, time(e.resetAt))
                        delay(maxOf(10_000L, e.resetAt - System.currentTimeMillis() + 5_000))
                    } catch (e: Exception) {
                        failed++
                        log += "${f.name}: ${e.message}"
                        break
                    }
                }
                downloading.remove(f.id)
                done++
                refreshStored()
            }
            bulk = null
            message = s(R.string.bulk_done, done - failed, failed)
        }
    }
    val bulkRunning get() = bulkJob?.isActive == true

    fun downloadStock() {
        val st = catalog?.stock ?: return
        downloading[PackageStore.STOCK_ID] = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { packages.downloadStock(st) { g, t -> if (t > 0) downloading[PackageStore.STOCK_ID] = g.toFloat() / t } }
                refreshStored()
            } catch (e: Exception) {
                message = s(R.string.err_download, "V15", e.message ?: e.toString())
            } finally {
                downloading.remove(PackageStore.STOCK_ID)
            }
        }
    }

    // ── the FM-1 ──────────────────────────────────────────────────────────────────────────────────────────

    fun midiDevices(): List<String> = usb.devices().map { io.github.orianbsilva.obsloader.fm1.MidiLink.nameOf(it) }

    fun probe() {
        if (probing || installing) return
        stopThru()
        probing = true
        viewModelScope.launch {
            try {
                val d = updater.find()
                if (d != null) {
                    device = d.id; deviceName = d.link.name
                    d.link.close()
                } else {
                    device = null; deviceName = null
                    message = notFound()
                }
            } finally {
                probing = false
            }
        }
    }

    private fun notFound(): String {
        val names = midiDevices()
        return s(R.string.err_notfound, if (names.isEmpty()) s(R.string.none) else names.joinToString(", "))
    }

    /** a package from the list (on the phone), from a file the person chose, or the V15 */
    fun prepare(f: Firmware) {
        val st = packages.stored(f.id) ?: return
        prepare(f.name + " " + f.version, st.file.readBytes(), f)
    }

    fun prepareRepo(repo: String) {
        val st = packages.stored(io.github.orianbsilva.obsloader.hub.RepoStore.idOf(repo)) ?: return
        prepare(repo + " " + st.version, st.file.readBytes(), null)
    }

    fun prepareStock() {
        val st = packages.stored(PackageStore.STOCK_ID) ?: return
        prepare("M-VAVE V15", st.file.readBytes(), null)
    }

    fun prepareFile(uri: Uri) {
        val raw = runCatching { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        if (raw == null) { message = s(R.string.err_read); return }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val sha = Wire.sha256(raw)
        val known = catalog?.firmwares?.firstOrNull { it.sha256 == sha }   // a file the hub checked: its card's rules
        prepare(name, raw, known)
    }

    private var pendingId: String? = null

    private fun prepare(label: String, raw: ByteArray, f: Firmware?) {
        if (installing || probing) return
        stopThru()
        pendingId = f?.id ?: if (Wire.sha256(raw) == Wire.STOCK_V15_SHA256) PackageStore.STOCK_ID else null
        val product = Wire.productOf(raw)
        if (product == null || !Regex("[^_]+_\\d+").matches(product)) { message = s(R.string.err_notpkg); return }
        val sha = Wire.sha256(raw)
        val official = sha == Wire.STOCK_V15_SHA256
        val checked = f != null && sha == f.sha256        // the hub checked it (its loader, M-VAVE's too)
        if (!Wire.hasLoaderMark(raw) && !official && !checked) { message = s(R.string.err_noloader); return }
        val warn = mutableListOf<String>()
        if (f != null && f.chip == "old") {
            if (chip == Chip.NEWER) { message = s(R.string.err_oldchip, f.name); return }
            if (chip == Chip.UNKNOWN) warn += s(R.string.warn_oldchip)
        }
        if (f == null && !official) warn += s(R.string.warn_unlisted)
        if (f != null && f.beta) warn += s(R.string.warn_beta)
        probing = true
        viewModelScope.launch {
            try {
                val d = updater.find()
                if (d == null) { device = null; message = notFound(); return@launch }
                device = d.id; deviceName = d.link.name
                d.link.close()
                if (d.id.loader) warn += s(R.string.warn_resume)
                pending = Pending(label, product, raw, d.id, d.link.name, warn)
            } finally {
                probing = false
            }
        }
    }

    fun cancelPending() { pending = null }

    /** the firmware the FM-1 runs, as far as the app knows: the one it last installed if the identity is still its,
     *  else the list's firmwares with that identity */
    fun running(): List<String> {
        val d = device ?: return emptyList()
        val last = prefs.getString("running", null)
        val cat = catalog?.firmwares.orEmpty()
        if (last != null && (cat.firstOrNull { it.id == last }?.product == d.text || (last == PackageStore.STOCK_ID && d.text == catalog?.stock?.product)))
            return listOf(last)
        return cat.filter { it.product == d.text }.map { it.id } + if (d.text == catalog?.stock?.product) listOf(PackageStore.STOCK_ID) else emptyList()
    }

    fun install() {
        val p = pending ?: return
        pending = null
        installing = true
        log.clear()
        step = null; progress = null
        viewModelScope.launch {
            try {
                val image = Wire.logicalImage(p.raw)
                val d = updater.find() ?: throw InstallError(InstallError.Code.NOT_FOUND, notFound())
                val got = updater.install(d, image, p.product) { st -> onStep(st) }
                device = Identity(got, Wire.modelOf(got), 0); step = s(R.string.step_done, got); progress = 1f
                pendingId?.let { prefs.edit().putLong("at.$it", System.currentTimeMillis()).putString("running", it).apply() }
                log += s(R.string.step_done, got)
            } catch (e: InstallError) {
                step = s(R.string.step_failed); progress = null
                log += e.message ?: e.code.name
                message = e.message
            } catch (e: Exception) {
                step = s(R.string.step_failed); progress = null
                log += e.toString()
                message = e.toString()
            } finally {
                installing = false
            }
        }
    }

    private fun onStep(st: Step) {
        val t = when (st) {
            is Step.Start -> s(R.string.step_start, st.on)
            is Step.Check -> s(R.string.step_check, st.reads)
            Step.Loader -> s(R.string.step_loader)
            is Step.Write -> s(R.string.step_write, st.percent)
            Step.Reboot -> s(R.string.step_reboot)
            is Step.Done -> s(R.string.step_done, st.ident)
        }
        progress = if (st is Step.Write) st.percent / 100f else null
        if (step != t && st !is Step.Write && st !is Step.Check) log += t
        step = t
    }

    private fun time(ms: Long) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))
}
