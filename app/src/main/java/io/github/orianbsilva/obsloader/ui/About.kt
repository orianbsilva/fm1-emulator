// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: 2026 Orian Beltrame. What the app is, and whose work it stands on.
package io.github.orianbsilva.obsloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.orianbsilva.obsloader.AppModel
import io.github.orianbsilva.obsloader.BuildConfig
import io.github.orianbsilva.obsloader.R
import io.github.orianbsilva.obsloader.hub.HUB

@Composable
fun AboutScreen(m: AppModel, mod: Modifier, open: (String) -> Unit) {
    val uri = LocalUriHandler.current
    @Composable fun Link(text: String, url: String, inApp: Boolean = false) =
        Text(text, Modifier.clickable { if (inApp) open(url) else uri.openUri(url) }.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
    @Composable fun Credit(title: String, who: String, what: String, content: @Composable () -> Unit) {
        Card(Modifier.padding(vertical = 5.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Amber)
                Text(who, style = MaterialTheme.typography.bodyMedium)
                Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("FM1 Emulator " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Amber)
        Text(stringResource(R.string.about_what), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.credits_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Credit("fm1-emu", "Joakim Eriksson", stringResource(R.string.credit_fm1emu_what)) {
            Link("github.com/joakimeriksson/fm1-emu", "https://github.com/joakimeriksson/fm1-emu")
            Link(stringResource(R.string.credit_licence, "MIT"), "https://appassets.androidplatform.net/licenses/fm1-emu-MIT.txt", inApp = true)
        }
        Credit("The Firmware Hub", "Sven Trogus (zvenson)", stringResource(R.string.about_list, m.catalog?.license ?: "CC-BY-4.0")) {
            Link("thefirmwarehub.com", HUB)
            Link("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
        }
        Credit(stringResource(R.string.about_fw_title), stringResource(R.string.credit_makers), stringResource(R.string.about_fw)) { }
        Credit("Felucca", "Leo Kuroshita (@kurogedelic), Hügelton Instruments", stringResource(R.string.credit_felucca_what)) {
            Link("github.com/hugelton/Felucca", "https://github.com/hugelton/Felucca")
        }
        Credit("ObsSynth FM-1", "Orian Beltrame", stringResource(R.string.credit_obssynth_what)) {
            Link("github.com/orianbsilva/obssynth-fm1", "https://github.com/orianbsilva/obssynth-fm1")
            Link("Hortator (DEADACTIVE)", "https://github.com/deadactive/hortator")
        }
        Credit(stringResource(R.string.about_app_title), "Orian Beltrame", stringResource(R.string.about_app)) {
            Link("github.com/orianbsilva/fm1-emulator", "https://github.com/orianbsilva/fm1-emulator")
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.about_trademarks), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.disclaimer), style = MaterialTheme.typography.bodySmall)
    }
}
