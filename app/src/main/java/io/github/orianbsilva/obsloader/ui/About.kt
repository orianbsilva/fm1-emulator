// SPDX-License-Identifier: GPL-3.0-only
package io.github.orianbsilva.obsloader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    @Composable fun Link(text: String, url: String) =
        Text(text, Modifier.clickable { uri.openUri(url) }.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
    @Composable fun Para(text: String) { Text(text, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)) }
    Column(mod.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("ObsLoader " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Amber)
        Para(stringResource(R.string.about_what))
        Text(stringResource(R.string.about_list_title), style = MaterialTheme.typography.titleSmall)
        Para(stringResource(R.string.about_list, m.catalog?.license ?: "CC-BY-4.0"))
        Link("The Firmware Hub – thefirmwarehub.com", HUB)
        Link("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.about_fw_title), style = MaterialTheme.typography.titleSmall)
        Para(stringResource(R.string.about_fw))
        Text(stringResource(R.string.about_app_title), style = MaterialTheme.typography.titleSmall)
        Para(stringResource(R.string.about_app))
        Link("github.com/orianbsilva/obsloader", "https://github.com/orianbsilva/obsloader")
        Link("ObsSynth FM-1 (github.com/orianbsilva/obssynth-fm1)", "https://github.com/orianbsilva/obssynth-fm1")
        Link("Felucca (Hügelton Instruments)", "https://github.com/hugelton/Felucca")
        Spacer(Modifier.height(10.dp))
        Para(stringResource(R.string.about_trademarks))
        Para(stringResource(R.string.disclaimer))
    }
}
