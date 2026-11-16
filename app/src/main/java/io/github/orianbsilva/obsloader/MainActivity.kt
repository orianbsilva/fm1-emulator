// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. Firmwares for the M-VAVE FM-1 from The Firmware Hub's list, installed over USB.
package io.github.orianbsilva.obsloader

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.orianbsilva.obsloader.ui.AboutScreen
import io.github.orianbsilva.obsloader.ui.DetailScreen
import io.github.orianbsilva.obsloader.ui.DeviceScreen
import io.github.orianbsilva.obsloader.ui.FirmwaresScreen
import io.github.orianbsilva.obsloader.ui.ObsTheme
import io.github.orianbsilva.obsloader.ui.SimScreen
import io.github.orianbsilva.obsloader.ui.WebScreen

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObsTheme {
                val m = model
                var tab by rememberSaveable { mutableStateOf(0) }
                var detail by rememberSaveable { mutableStateOf<String?>(null) }
                var web by rememberSaveable { mutableStateOf<String?>(null) }
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(m.message) { m.message?.let { snack.showSnackbar(it); m.message = null } }
                // the screen stays on while the FM-1 is written
                LaunchedEffect(m.installing) {
                    if (m.installing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler(web != null || detail != null) { if (web != null) web = null else detail = null }
                val w = web
                if (w != null) {
                    WebScreen(w) { web = null }
                    return@ObsTheme
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(
                                Triple(R.string.tab_firmwares, Icons.Default.List, 0),
                                Triple(R.string.tab_device, Icons.Default.Usb, 1),
                                Triple(R.string.tab_sim, Icons.Default.PlayArrow, 2),
                                Triple(R.string.tab_about, Icons.Default.Info, 3),
                            )
                            for ((label, icon, i) in items) NavigationBarItem(
                                selected = tab == i, onClick = { tab = i; detail = null },
                                icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
                        }
                    },
                ) { pad ->
                    val mod = Modifier.padding(pad)
                    val d = detail?.let { id -> m.catalog?.firmwares?.firstOrNull { it.id == id } }
                    when {
                        tab == 0 && d != null -> DetailScreen(m, d, mod, onBack = { detail = null }, onWeb = { web = it }, onInstall = { m.prepare(d); tab = 1 })
                        tab == 0 -> FirmwaresScreen(m, mod) { detail = it.id }
                        tab == 1 -> DeviceScreen(m, mod)
                        tab == 2 -> SimScreen(m, mod) { web = it }
                        else -> AboutScreen(m, mod) { web = it }
                    }
                }
            }
        }
    }
}
