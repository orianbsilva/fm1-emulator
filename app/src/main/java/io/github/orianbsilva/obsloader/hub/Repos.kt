// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. Firmwares that are not on The Firmware Hub's list (yet): a GitHub repository the
// person adds, its newest release with a .fwsc attached. Nobody checked these (no list sha256, identity or loader
// check): they play in the emulator as they are; installing one on a real FM-1 asks for the update loader.
package io.github.orianbsilva.obsloader.hub

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** a repository's newest package */
data class RepoRelease(val repo: String, val tag: String, val name: String, val asset: String, val url: String, val size: Long, val page: String)

class RepoStore(private val prefs: SharedPreferences, private val packages: PackageStore) {
    fun list(): List<String> = prefs.getString("repos", "")!!.split('\n').filter { it.isNotBlank() }

    private fun save(l: List<String>) = prefs.edit().putString("repos", l.distinct().joinToString("\n")).apply()

    fun remove(repo: String) { save(list() - repo); packages.delete(idOf(repo)) }

    /** the newest release (pre-releases too, as the hub does) that has a .fwsc: from the repository's public feed and
     *  release pages (no GitHub API, whose limit for apps without an account is 60 calls an hour); the API only when
     *  those give nothing */
    fun latest(repo: String): RepoRelease {
        for (tag in PackageStore.releaseTags(repo).take(8)) {
            val files = PackageStore.releaseFiles(repo, tag)
            if (files.isNotEmpty()) {
                val url = files.first()
                return RepoRelease(repo, tag, tag, url.substringAfterLast('/'), url, 0, "https://github.com/$repo/releases/tag/$tag")
            }
        }
        return latestApi(repo)
    }

    private fun latestApi(repo: String): RepoRelease {
        val arr = JSONArray(PackageStore.getText("https://api.github.com/repos/$repo/releases?per_page=10"))
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val assets = r.getJSONArray("assets")
            for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.getString("name").endsWith(".fwsc", true))
                    return RepoRelease(repo, r.getString("tag_name"), r.optString("name").ifBlank { r.getString("tag_name") },
                        a.getString("name"), a.getString("browser_download_url"), a.optLong("size"), r.optString("html_url"))
            }
        }
        throw IllegalStateException("$repo: no release with a .fwsc")
    }

    /** add it (or update it): its newest package downloaded; the repository's name as the firmware's */
    fun fetch(repo: String): Stored {
        val r = latest(repo)
        val raw = PackageStore.getBytes(r.url, r.size)
        if (io.github.orianbsilva.obsloader.fm1.Wire.productOf(raw)?.let { Regex("[^_]+_\\d+").matches(it) } != true)
            throw IllegalStateException("${r.asset}: not an FM-1 package")
        val st = packages.keepExternal(idOf(repo), r.tag, raw, "see the repository",
            "https://github.com/$repo/tree/${r.tag}", r.page)
        save(list() + repo)
        return st
    }

    companion object {
        /** "owner/repo" out of what the person typed: owner/repo, or a github.com address */
        fun parse(text: String): String? {
            val t = text.trim().removeSuffix("/").removeSuffix(".git")
            val m = Regex("(?:https?://)?(?:www\\.)?(?:github\\.com/)?([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})(?:/.*)?").matchEntire(t)
                ?: return null
            return m.groupValues[1] + "/" + m.groupValues[2]
        }
        fun idOf(repo: String) = "gh_" + repo.replace('/', '_')
    }
}
