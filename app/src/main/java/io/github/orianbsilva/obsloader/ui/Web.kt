// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The simulators: ObsSynth FM-1's, built into the app (it runs offline), and the
// browser versions the makers publish (their "play" pages), in a WebView.
package io.github.orianbsilva.obsloader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
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
            "woff2" -> "font/woff2"; "wav" -> "audio/wav"; else -> "application/octet-stream"
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
fun SimScreen(m: AppModel, mod: Modifier, play: (String) -> Unit, open: (String) -> Unit) {
    val plays = m.catalog?.firmwares?.filter { it.play != null }.orEmpty()
    LazyColumn(mod.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(io.github.orianbsilva.obsloader.emu.Cores.ALL, key = { "core:" + it.id }) { c ->
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(c.name + " " + c.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Amber)
                    Text(stringResource(R.string.emu_native_help), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.sim_midi), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ play(c.id) }) { Text(stringResource(R.string.emu_play_native)) }
                        if (c.id == "obssynth-fm1") androidx.compose.material3.OutlinedButton({ open(builtInSim()) }) { Text(stringResource(R.string.sim_web)) }
                    }
                    m.emulator.failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(c.credits, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text(stringResource(R.string.sim_others, plays.size), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall) }
        items(plays, key = { it.id }) { f ->
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
