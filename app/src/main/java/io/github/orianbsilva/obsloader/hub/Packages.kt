// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The packages on the phone: each from its maker's GitHub release (never from the
// hub), kept only if its sha256 is the list's; with it, what the license asks to travel along (the license's name,
// the source at its tag, the release). A firmware that leaves the list leaves the phone too.
package io.github.orianbsilva.obsloader.hub

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RateLimited(val resetAt: Long) : Exception("GitHub's limit for apps without an account: again at $resetAt")

data class Stored(val id: String, val version: String, val sha256: String, val file: File, val lic: String,
                  val source: String?, val release: String?)

class PackageStore(root: File) {
    private val dir = File(root, "fw").apply { mkdirs() }

    private fun pkg(id: String) = File(dir, "$id.fwsc")
    private fun meta(id: String) = File(dir, "$id.json")

    fun stored(id: String): Stored? = runCatching {
        val m = JSONObject(meta(id).readText())
        val f = pkg(id)
        if (!f.exists()) null
        else Stored(id, m.getString("version"), m.getString("sha256"), f, m.optString("lic"),
                    m.optString("source").ifEmpty { null }, m.optString("release").ifEmpty { null })
    }.getOrNull()

    /** stored and still the list's version */
    fun isCurrent(f: Firmware) = stored(f.id)?.sha256 == f.sha256

    fun all(): List<Stored> = dir.listFiles { x -> x.name.endsWith(".json") }.orEmpty().mapNotNull { stored(it.nameWithoutExtension) }

    fun delete(id: String) { pkg(id).delete(); meta(id).delete() }

    /** what dropped out of the list goes; an old version of what stays is kept until its new one comes */
    fun prune(cat: Catalog): Int {
        val ids = cat.firmwares.filter { it.fromRelease }.map { it.id }.toSet()
        val gone = all().filter { it.id !in ids && it.id != STOCK_ID }
        gone.forEach { delete(it.id) }
        return gone.size
    }

    /** the maker's release asset's address: the asset the list names, else the one in the tag's release that matches */
    private fun resolve(f: Firmware): String {
        val api = "https://api.github.com/repos/${f.repo}/releases"
        if (f.assetId != null) {
            val j = JSONObject(getText("$api/assets/${f.assetId}"))
            return j.getString("browser_download_url")
        }
        val rel = JSONObject(getText("$api/tags/${f.tag}"))
        val assets = rel.getJSONArray("assets")
        val pat = f.assetPattern?.let { Regex(it) }
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.getString("name")
            if (if (pat != null) pat.matches(n) else n.endsWith(".fwsc")) return a.getString("browser_download_url")
        }
        throw IllegalStateException("no .fwsc in ${f.repo}'s release ${f.tag}")
    }

    /** the firmware's package from its maker's release, checked against the list's sha256 */
    fun download(f: Firmware, progress: (Long, Long) -> Unit = { _, _ -> }): Stored {
        require(f.fromRelease) { "${f.name} installs from its maker's page" }
        val raw = getBytes(resolve(f), f.size, progress)
        val sha = sha256(raw)
        if (sha != f.sha256) throw SecurityException("${f.name}: the release's file is not the one the hub checked (sha256 $sha)")
        return keep(f.id, f.version, sha, raw, f.lic, f.srcref ?: f.source, f.release)
    }

    /** M-VAVE's V15, from M-VAVE's own repository, checked against the list's sha256 */
    fun downloadStock(s: Stock, progress: (Long, Long) -> Unit = { _, _ -> }): Stored {
        val raw = getBytes(s.rawUrl, s.size, progress)
        val sha = sha256(raw)
        if (sha != s.sha256) throw SecurityException("V15: not M-VAVE's file (sha256 $sha)")
        return keep(STOCK_ID, "V15", sha, raw, "Apache-2.0 (M-VAVE)", s.source, s.source)
    }

    private fun keep(id: String, version: String, sha: String, raw: ByteArray, lic: String, source: String?, release: String?): Stored {
        val tmp = File(dir, "$id.part")
        tmp.writeBytes(raw)
        tmp.renameTo(pkg(id))
        meta(id).writeText(JSONObject().put("version", version).put("sha256", sha).put("lic", lic)
            .put("source", source ?: "").put("release", release ?: "").toString())
        return stored(id)!!
    }

    companion object {
        const val STOCK_ID = "_mvave_v15"

        fun sha256(b: ByteArray) = io.github.orianbsilva.obsloader.fm1.Wire.sha256(b)

        private fun conn(url: String): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (url.startsWith("https://api.github.com/")) c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            val code = c.responseCode
            if ((code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0")
                throw RateLimited((c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0L) * 1000)
            if (code !in 200..299) throw java.io.IOException("$url: HTTP $code")
            return c
        }

        fun getText(url: String) = conn(url).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        fun getBytes(url: String, expect: Long = 0, progress: (Long, Long) -> Unit = { _, _ -> }): ByteArray {
            val c = conn(url)
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else expect
            val out = java.io.ByteArrayOutputStream(if (total in 1..64_000_000) total.toInt() else 1 shl 20)
            c.inputStream.use { inp ->
                val buf = ByteArray(32 * 1024)
                var got = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (got > 16L shl 20) throw java.io.IOException("$url: larger than a firmware")
                    progress(got, total)
                }
            }
            return out.toByteArray()
        }
    }
}

/** the makers' pictures (their fmhub.json "image", from their repositories), kept on the phone */
class PictureStore(root: File) {
    private val dir = File(root, "pic").apply { mkdirs() }
    fun get(f: Firmware): ByteArray? {
        val url = f.imageUrl ?: return null
        val file = File(dir, f.id + "-" + Integer.toHexString(url.hashCode()))
        if (file.exists()) return file.readBytes()
        return runCatching {
            val b = PackageStore.getBytes(url)
            if (b.size > 1_000_000) null else b.also { file.writeBytes(it) }
        }.getOrNull()
    }
}
