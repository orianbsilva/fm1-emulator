// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. The hub's list, and one firmware's card.
package io.github.orianbsilva.obsloader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.orianbsilva.obsloader.AppModel
import io.github.orianbsilva.obsloader.Chip
import io.github.orianbsilva.obsloader.R
import io.github.orianbsilva.obsloader.hub.Firmware
import io.github.orianbsilva.obsloader.hub.HUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val KINDS = listOf("synth", "groove", "drums", "chords", "tracker", "other")

fun colourOf(f: Firmware): Color =
    runCatching { Color(android.graphics.Color.parseColor(f.color)) }.getOrNull()
        ?: Color.hsv((f.id.hashCode() and 0x7fffffff) % 360f, 0.45f, 0.8f)

@Composable
fun Picture(m: AppModel, f: Firmware, size: Int) {
    val bmp by produceState<android.graphics.Bitmap?>(null, f.id) {
        value = withContext(Dispatchers.IO) { m.pictures.get(f)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }
    val b = bmp
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0C0F0D)), contentAlignment = Alignment.Center) {
        if (b != null) Image(b.asImageBitmap(), f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(f.name.take(2).uppercase(), color = colourOf(f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun FirmwaresScreen(m: AppModel, mod: Modifier, open: (Firmware) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var mine by rememberSaveable { mutableStateOf(false) }
    val cat = m.catalog
    Column(mod.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.search)) })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mine, { mine = !mine }, { Text(stringResource(R.string.on_phone)) })
            for (k in KINDS) FilterChip(kind == k, { kind = if (kind == k) null else k }, { Text(k) })
        }
        if (cat == null) {
            Column(Modifier.padding(16.dp)) {
                Text(if (m.loading) stringResource(R.string.loading) else m.catalogError ?: "")
                if (!m.loading) Button({ m.loadCatalog() }) { Text(stringResource(R.string.retry)) }
            }
            return@Column
        }
        val list = cat.firmwares.filter { f ->
            (kind == null || f.kind == kind) && (!mine || f.id in m.onPhone) &&
                (query.isBlank() || listOf(f.name, f.author, f.what).any { it.contains(query.trim(), true) })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.list_head, list.size, cat.updated.take(10)), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ m.downloadAll() }) { Text(stringResource(if (m.bulkRunning) R.string.stop else R.string.download_all)) }
        }
        m.bulk?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { f -> FirmwareRow(m, f) { open(f) } }
            item { Attribution() }
        }
    }
}

@Composable
private fun FirmwareRow(m: AppModel, f: Firmware, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Picture(m, f, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontWeight = FontWeight.Bold, color = colourOf(f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Text("  " + f.version + if (f.beta) " β" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.by, f.author) + " · " + f.kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(f.what, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val tags = buildList {
                    when (m.onPhone[f.id]) { null -> Unit; f.sha256 -> add(stringResource(R.string.tag_on_phone)); else -> add(stringResource(R.string.tag_update)) }
                    if (f.play != null) add(stringResource(R.string.tag_play))
                    if (f.chip == "old") add(stringResource(R.string.tag_oldchip))
                    if (!f.fromRelease) add(stringResource(R.string.tag_maker_page))
                }
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                m.downloading[f.id]?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
fun Attribution() {
    val uri = LocalUriHandler.current
    Text(stringResource(R.string.attribution), Modifier.fillMaxWidth().clickable { uri.openUri(HUB) }.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Fact(label: String, value: String, link: String? = null) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f).then(if (link != null) Modifier.clickable { uri.openUri(link) } else Modifier),
            style = MaterialTheme.typography.bodySmall, color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun DetailScreen(m: AppModel, f: Firmware, mod: Modifier, onBack: () -> Unit, onWeb: (String) -> Unit, onInstall: () -> Unit, onPlay: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picture(m, f, 96)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(f.name, style = MaterialTheme.typography.headlineSmall, color = colourOf(f), fontWeight = FontWeight.Bold)
                Text(f.version + (if (f.beta) " (beta)" else "") + " · " + f.date, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.by, f.author), Modifier.clickable { uri.openUri(f.repoUrl) }, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(f.what)
        Text(stringResource(if (f.textByMaker) R.string.text_by_maker else R.string.text_by_hub), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        f.notice?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.height(12.dp))
        if (f.fromRelease) Button(onPlay, Modifier.fillMaxWidth(), enabled = f.id !in m.downloading) {
            Text(stringResource(if (m.onPhone[f.id] == f.sha256) R.string.play_in_emu else R.string.play_in_emu_get))
        }
        Spacer(Modifier.height(8.dp))
        val stored = m.onPhone[f.id]
        when {
            f.linkOnly || f.externalInstall || !f.fromRelease -> {
                Text(stringResource(R.string.maker_page_only), style = MaterialTheme.typography.bodySmall)
                f.release?.let { r -> Button({ uri.openUri(r) }) { Text(stringResource(R.string.open_maker_page)) } }
            }
            f.id in m.downloading -> LinearProgressIndicator({ m.downloading[f.id] ?: 0f }, Modifier.fillMaxWidth())
            stored == f.sha256 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onInstall, enabled = !m.installing && !(f.chip == "old" && m.chip == Chip.NEWER)) { Text(stringResource(R.string.install_on_fm1)) }
                OutlinedButton({ m.forget(f.id) }) { Text(stringResource(R.string.remove)) }
            }
            else -> Button({ m.download(f) }) { Text(stringResource(if (stored != null) R.string.download_update else R.string.download, f.size / 1024)) }
        }
        if (f.chip == "old" && m.chip == Chip.NEWER) Text(stringResource(R.string.err_oldchip, f.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            f.play?.let { p -> AssistChip({ onWeb(p) }, { Text(stringResource(R.string.play_browser)) }) }
            f.editor?.let { e -> AssistChip({ onWeb(e) }, { Text(stringResource(R.string.editor)) }) }
            f.cheat?.let { c -> AssistChip({ uri.openUri(c) }, { Text(stringResource(R.string.cheat)) }) }
            f.talk?.let { t -> AssistChip({ uri.openUri(t) }, { Text(stringResource(R.string.talk)) }) }
        }
        Fact(stringResource(R.string.f_licence), f.lic)
        f.source?.let { Fact(stringResource(R.string.f_source), (f.srcref ?: it).removePrefix("https://"), f.srcref ?: it) }
        f.release?.let { Fact(stringResource(R.string.f_release), it.removePrefix("https://"), it) }
        Fact(stringResource(R.string.f_chip), stringResource(when (f.chip) { "new" -> R.string.chip_new; "any" -> R.string.chip_any; else -> R.string.chip_old }))
        Fact(stringResource(R.string.f_identity), f.product)
        Fact(stringResource(R.string.f_loader), if (f.loader == "mvave") "M-VAVE (V15)" else "Felucca")
        Fact(stringResource(R.string.f_sha), f.sha256.take(16) + "…")
        Fact(stringResource(R.string.f_stars), "${f.stars} ★ · ${f.installs}")
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.disclaimer_short), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Attribution()
    }
}
