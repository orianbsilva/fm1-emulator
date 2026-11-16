// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. The simulators: ObsSynth FM-1's, built into the app (it runs offline), and the
// browser versions the makers publish (their "play" pages), in a WebView.
package io.github.orianbsilva.obsloader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.orianbsilva.obsloader.midi.MidiBridge
import io.github.orianbsilva.obsloader.AppModel
import io.github.orianbsilva.obsloader.R
import java.util.Locale

const val APP_HOST = "appassets.androidplatform.net"

/** the built-in simulator's page, in the person's language */
fun builtInSim() = "https://$APP_HOST/sim/" + if (Locale.getDefault().language == "pt") "pt.html" else "index.html"

/** files under assets/ at https://appassets.androidplatform.net/..., with the types a page needs (.wasm too) */
private class Assets(private val ctx: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = runCatching {
        val type = when (path.substringAfterLast('.').lowercase()) {
            "html" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"
            "wasm" -> "application/wasm"; "png" -> "image/png"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"
            "woff2" -> "font/woff2"; "wav" -> "audio/wav"; "txt", "md" -> "text/plain"; else -> "application/octet-stream"
        }
        WebResourceResponse(type, if (type.startsWith("text") || type.endsWith("json")) "utf-8" else null, ctx.assets.open(path))
    }.getOrNull()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, close: () -> Unit) {
    val ctx = LocalContext.current
    val bridge = remember(url) { MidiBridge(ctx.applicationContext) }
    DisposableEffect(bridge) { onDispose { bridge.close() } }
    val origin = MidiBridge.originOf(url)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.Default.Close, stringResource(R.string.back)) }
            Text(url.removePrefix("https://").substringBefore('?'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            val loader = WebViewAssetLoader.Builder().setDomain(APP_HOST).addPathHandler("/", Assets(ctx)).build()
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(req.url)
                    // the page stays on its own site (the one MIDI was given to); a link elsewhere opens in the browser
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                        if (MidiBridge.originOf(req.url.toString()) == origin) return false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(req.url.toString()))) }
                        return true
                    }
                }
                if (origin != null) bridge.attach(this, setOf(origin))
                loadUrl(url)
            }
        }, onRelease = { it.destroy() })
    }
}

@Composable
fun SimScreen(m: AppModel, mod: Modifier, open: (String) -> Unit) {
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        u?.let { m.playFile(it) }
    }
    val list = remember(m.onPhone.toMap(), m.catalog, m.repoList.toList()) { m.playable() }
    val plays = m.catalog?.firmwares?.filter { it.play != null }.orEmpty()
    LazyColumn(mod.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Text(stringResource(R.string.emu_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Amber)
            Text(stringResource(R.string.emu_help), style = MaterialTheme.typography.bodySmall)
            // the engine's credit, where the emulator is
            Text(stringResource(R.string.credit_fm1emu), Modifier.padding(top = 4.dp).clickable { uri.openUri("https://github.com/joakimeriksson/fm1-emu") },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            m.emulator.failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        items(list, key = { it.id }) { p ->
            val f = m.catalog?.firmwares?.firstOrNull { "hub:" + it.id == p.id }
            Card(Modifier.fillMaxWidth().clickable { m.playing = p }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (f != null) Picture(m, f, 44) else androidx.compose.foundation.layout.Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Color(0xFF0C0F0D)), contentAlignment = Alignment.Center) {
                        Text(p.name.take(2).uppercase(), color = Amber, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.version.isNotEmpty()) "  " + p.version else "", fontWeight = FontWeight.Bold, color = if (f != null) colourOf(f) else Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            p.id.startsWith("builtin:") -> stringResource(R.string.emu_builtin)
                            p.id.startsWith("file:") -> stringResource(R.string.emu_file)
                            p.id.startsWith("repo:") -> stringResource(R.string.repo_off_hub, p.id.removePrefix("repo:"))
                            p.author.isNotEmpty() -> stringResource(R.string.by, p.author)
                            else -> ""
                        }, style = MaterialTheme.typography.labelSmall, color = if (p.id.startsWith("repo:")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.id.startsWith("repo:") || p.id.startsWith("file:"))
                        androidx.compose.material3.TextButton({ if (p.id.startsWith("repo:")) m.removeRepo(p.id.removePrefix("repo:")) else m.forgetFile(p) }) { Text("×") }
                    Button({ m.playing = p }) { Text(stringResource(R.string.emu_play)) }
                }
            }
        }
        item {
            var adding by remember { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedButton({ adding = true }) { Text(stringResource(R.string.repo_add)) }
                androidx.compose.material3.OutlinedButton({ pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.emu_open_file)) }
                if (m.repoList.isNotEmpty()) androidx.compose.material3.TextButton({ m.checkRepos(true) }) { Text(stringResource(R.string.repo_check)) }
            }
            m.downloading.keys.firstOrNull { it.startsWith("gh_") }?.let { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) }
            Text(stringResource(R.string.emu_more), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (adding) RepoDialog(m) { adding = false }
        }
        item { Text(stringResource(R.string.sim_others, plays.size), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall) }
        items(plays, key = { "web:" + it.id }) { f ->
            Row(Modifier.fillMaxWidth().clickable { open(f.play!!) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Picture(m, f, 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = colourOf(f), fontWeight = FontWeight.Bold)
                    Text(f.play!!.removePrefix("https://").substringBefore('/'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text(stringResource(R.string.sim_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RepoDialog(m: AppModel, close: () -> Unit) {
    var text by remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.repo_add)) }, text = {
        Column {
            Text(stringResource(R.string.repo_help), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                placeholder = { Text("owner/repo") })
            Text(stringResource(R.string.repo_warn), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button({ m.addRepo(text); close() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.repo_add_go)) } },
        dismissButton = { androidx.compose.material3.TextButton(close) { Text(stringResource(R.string.cancel)) } })
}
