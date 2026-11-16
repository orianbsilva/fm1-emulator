// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. The FM-1 on USB: what it runs, its flash chip, installing, the way back.
package io.github.orianbsilva.obsloader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.orianbsilva.obsloader.AppModel
import io.github.orianbsilva.obsloader.Chip
import io.github.orianbsilva.obsloader.R
import io.github.orianbsilva.obsloader.hub.PackageStore

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun DeviceScreen(m: AppModel, mod: Modifier) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { m.prepareFile(it) } }
    val busy = m.installing || m.probing
    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (!m.usb.available) Text(stringResource(R.string.no_midi), color = MaterialTheme.colorScheme.error)
        Section(stringResource(R.string.dev_title)) {
            val d = m.device
            if (d == null) Text(stringResource(R.string.dev_none), style = MaterialTheme.typography.bodySmall)
            else {
                val name = m.catalog?.firmwares?.firstOrNull { it.product == d.text }?.let { it.name + " " + it.version }
                Text(name ?: d.text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text(d.text + "  ·  " + (m.deviceName ?: "") + if (d.loader) "  ·  " + stringResource(R.string.dev_loader) else "",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton({ m.probe() }, enabled = !busy) { Text(stringResource(if (m.probing) R.string.dev_looking else R.string.dev_find)) }
            Text(stringResource(R.string.dev_cable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(stringResource(R.string.chip_title)) {
            Text(stringResource(R.string.chip_help), style = MaterialTheme.typography.bodySmall)
            for ((c, label) in listOf(Chip.UNKNOWN to R.string.chip_unknown, Chip.FIRST to R.string.chip_first, Chip.NEWER to R.string.chip_newer))
                Row(Modifier.fillMaxWidth().selectable(m.chip == c) { m.chooseChip(c) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(m.chip == c, { m.chooseChip(c) })
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
        }
        Section(stringResource(R.string.lib_title)) {
            // the firmwares on the phone: the one on the FM-1 first, then the last installed; one touch switches
            val running = m.running()
            val mine = m.catalog?.firmwares?.filter { m.onPhone[it.id] == it.sha256 }.orEmpty()
                .sortedWith(compareByDescending<io.github.orianbsilva.obsloader.hub.Firmware> { it.id in running }.thenByDescending { m.installedAt(it.id) })
            if (mine.isEmpty()) Text(stringResource(R.string.inst_none), style = MaterialTheme.typography.bodySmall)
            for (f in mine) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Picture(m, f, 36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name + " " + f.version, color = colourOf(f), fontWeight = FontWeight.Bold)
                    val at = m.installedAt(f.id)
                    Text(when {
                        f.id in running -> stringResource(R.string.lib_running)
                        at > 0 -> stringResource(R.string.lib_installed, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at)))
                        else -> f.product
                    }, style = MaterialTheme.typography.labelSmall, color = if (f.id in running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val blocked = f.chip == "old" && m.chip == Chip.NEWER
                if (f.id in running) TextButton({ m.prepare(f) }, enabled = !busy && !blocked) { Text(stringResource(R.string.lib_reinstall)) }
                else Button({ m.prepare(f) }, enabled = !busy && !blocked) { Text(stringResource(R.string.lib_switch)) }
            }
            for (repo in m.repoList) {
                val st = m.packages.stored(io.github.orianbsilva.obsloader.hub.RepoStore.idOf(repo)) ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(repo.substringAfter('/') + " " + st.version, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.repo_off_hub, repo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Button({ m.prepareRepo(repo) }, enabled = !busy) { Text(stringResource(R.string.lib_switch)) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.inst_file)) }
        }
        Section(stringResource(R.string.kb_title)) {
            Text(stringResource(R.string.kb_help), style = MaterialTheme.typography.bodySmall)
            androidx.compose.runtime.LaunchedEffect(Unit) { m.refreshKeyboards() }
            val on = m.thruFrom
            if (on != null) {
                Text(stringResource(R.string.kb_active, on), color = MaterialTheme.colorScheme.secondary)
                OutlinedButton({ m.stopThru() }) { Text(stringResource(R.string.kb_stop)) }
            } else {
                if (m.keyboards.isEmpty()) Text(stringResource(R.string.kb_none), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((id, name) in m.keyboards) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f))
                    TextButton({ m.startThru(id) }, enabled = !busy) { Text(stringResource(R.string.kb_play)) }
                }
                TextButton({ m.refreshKeyboards() }) { Text(stringResource(R.string.kb_refresh)) }
            }
        }
        Section(stringResource(R.string.back_title)) {
            Text(stringResource(R.string.back_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            val have = PackageStore.STOCK_ID in m.onPhone
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!have) OutlinedButton({ m.downloadStock() }, enabled = m.catalog?.stock != null && PackageStore.STOCK_ID !in m.downloading) { Text(stringResource(R.string.back_get)) }
                else Button({ m.prepareStock() }, enabled = !busy) { Text(stringResource(R.string.back_install)) }
            }
            Text(stringResource(R.string.back_rescue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.step != null || m.log.isNotEmpty()) Section(stringResource(R.string.progress_title)) {
            m.step?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (m.installing) {
                val p = m.progress
                if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text(stringResource(R.string.dont_unplug), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            for (l in m.log.takeLast(12)) Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    m.pending?.let { p ->
        AlertDialog(
            onDismissRequest = { m.cancelPending() },
            title = { Text(stringResource(R.string.confirm_title, p.label)) },
            text = {
                Column {
                    Text(stringResource(R.string.confirm_text, p.product, p.device.text, p.deviceName))
                    for (w in p.warnings) { Spacer(Modifier.height(6.dp)); Text(w, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { Button({ m.install() }) { Text(stringResource(R.string.install)) } },
            dismissButton = { TextButton({ m.cancelPending() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
