// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. The Firmware Hub's list (https://thefirmwarehub.com/catalog.json, CC BY 4.0, by
// its rules: https://thefirmwarehub.com/kb/#catalog): read at most once a day with a User-Agent that says who we are,
// kept on the phone; the makers' texts and pictures are theirs, each card names its maker and links its repository.
package io.github.orianbsilva.obsloader.hub

import io.github.orianbsilva.obsloader.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val HUB = "https://thefirmwarehub.com"
const val CATALOG_URL = "$HUB/catalog.json"
val USER_AGENT = "FM-1-Pocket/${BuildConfig.VERSION_NAME} (Android; +https://github.com/orianbsilva/fm1-pocket)"

/** one firmware of the list (the fields the app uses) */
data class Firmware(
    val id: String,
    val name: String,
    val author: String,
    val kind: String,
    val repo: String,
    val what: String,
    val textByMaker: Boolean,
    val version: String,
    val date: String,
    val beta: Boolean,
    val lic: String,
    val product: String,
    val chip: String,            // "new", "any", "old"
    val loader: String?,         // null: Felucca's; "mvave": M-VAVE's (built on V15)
    val sha256: String,
    val size: Long,
    val tag: String?,
    val assetId: Long?,
    val assetPattern: String?,
    val release: String?,
    val source: String?,
    val srcref: String?,
    val play: String?,
    val editor: String?,
    val cheat: String?,
    val talk: String?,
    val site: String?,
    val image: String?,          // the maker's picture (fmhub.json "image"), a path in the repository
    val color: String?,
    val notice: String?,
    val externalInstall: Boolean,
    val linkOnly: Boolean,
    val stars: Int,
    val installs: Int,
) {
    val repoUrl get() = "https://github.com/$repo"
    /** the package can come from the maker's GitHub release (a tag); otherwise only from the maker's page */
    val fromRelease get() = tag != null && !externalInstall && !linkOnly
    val imageUrl get() = image?.let { "https://raw.githubusercontent.com/$repo/HEAD/${it.trimStart('/')}" }
}

/** M-VAVE's official V15: the way back */
data class Stock(val sha256: String, val size: Long, val product: String, val source: String) {
    /** the file in M-VAVE's own repository (fm1_open), as raw bytes */
    val rawUrl get() = source.replace("/blob/", "/raw/")
}

data class Catalog(val updated: String, val firmwares: List<Firmware>, val stock: Stock?, val license: String, val attribution: String)

private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }

fun parseCatalog(text: String): Catalog {
    val j = JSONObject(text)
    val arr = j.optJSONArray("firmwares") ?: JSONArray()
    val list = ArrayList<Firmware>(arr.length())
    for (i in 0 until arr.length()) {
        val f = arr.getJSONObject(i)
        val hub = f.optJSONObject("fmhub")?.optJSONObject("data")
        val makerText = hub?.str("what")
        list += Firmware(
            id = f.optString("id"), name = f.optString("name"), author = f.optString("author"),
            kind = f.optString("kind", "other"), repo = f.optString("repo"),
            what = makerText ?: f.optString("what"), textByMaker = makerText != null,
            version = f.optString("version"), date = f.optString("date"), beta = f.optBoolean("beta"),
            lic = f.str("lic") ?: "GPL-3.0", product = f.optString("product"), chip = f.str("chip") ?: "old",
            loader = f.str("loader"), sha256 = f.optString("sha256").lowercase(), size = f.optLong("size"),
            tag = f.str("tag"), assetId = f.str("asset")?.substringBefore(' ')?.toLongOrNull(),
            assetPattern = f.str("assetPkg"), release = f.str("release"), source = f.str("source"),
            srcref = f.str("srcref"), play = f.str("play"), editor = f.str("editor"), cheat = f.str("cheat"),
            talk = f.str("talk"), site = f.str("site"), image = hub?.str("image"), color = f.str("color") ?: hub?.str("color"),
            notice = f.str("notice"), externalInstall = f.optBoolean("externalInstall"), linkOnly = f.optBoolean("linkOnly"),
            stars = f.optInt("stars"), installs = f.optInt("installs"),
        )
    }
    val st = j.optJSONObject("stock")?.let {
        Stock(it.optString("sha256").lowercase(), it.optLong("size"), it.optString("product"), it.optString("source"))
    }
    val lic = j.optJSONObject("license")
    return Catalog(j.optString("updated"), list, st, lic?.optString("license") ?: "CC-BY-4.0",
        lic?.optString("attribution") ?: "Firmware list: The Firmware Hub – $HUB")
}

/** the list on the phone: read from the hub at most once a day */
class CatalogStore(private val dir: File) {
    private val file = File(dir, "catalog.json")
    private val stamp = File(dir, "catalog.fetched")

    fun cached(): Catalog? = runCatching { if (file.exists()) parseCatalog(file.readText()) else null }.getOrNull()

    fun lastFetch(): Long = runCatching { stamp.readText().trim().toLong() }.getOrDefault(0L)

    fun mayFetch(now: Long = System.currentTimeMillis()) = now - lastFetch() >= DAY

    /** the hub's list, fetched if a day has passed (or there is none yet); the cached one otherwise */
    fun load(): Catalog? {
        if (!mayFetch() && file.exists()) return cached()
        return try {
            val c = URL(CATALOG_URL).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val cat = parseCatalog(text)          // (only a list that parses replaces the one we have)
            dir.mkdirs()
            File(dir, "catalog.json.part").apply { writeText(text) }.renameTo(file)
            stamp.writeText(System.currentTimeMillis().toString())
            cat
        } catch (e: Exception) {
            cached() ?: throw e
        }
    }

    companion object { const val DAY = 24L * 3600 * 1000 }
}
