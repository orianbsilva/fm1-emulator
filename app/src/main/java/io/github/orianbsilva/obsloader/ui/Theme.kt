// SPDX-License-Identifier: GPL-3.0-only
package io.github.orianbsilva.obsloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// the ObsSynth panels' dark ground and amber (the FM-1 firmware's card colour)
val Amber = Color(0xFFEEA139)
private val scheme = darkColorScheme(
    primary = Amber, onPrimary = Color(0xFF1A1206),
    secondary = Color(0xFF6FC3A0), onSecondary = Color(0xFF0B1A14),
    background = Color(0xFF121614), onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF161B18), onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF1F2622), onSurfaceVariant = Color(0xFFB4B0A6),
    surfaceContainer = Color(0xFF1A201C), error = Color(0xFFFF7B6B),
)

@Composable
fun ObsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
