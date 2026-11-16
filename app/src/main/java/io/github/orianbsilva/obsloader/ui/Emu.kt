// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The FM-1 on the phone's screen: the firmware's own screen, the knobs (drag up and
// down; MIDI learn gives one to a controller's CC), the buttons with their lights, OCT and the 27 keys (several
// fingers, sliding from key to key). Upright: the panel above the keys; sideways: as the FM-1 lies.
package io.github.orianbsilva.obsloader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.orianbsilva.obsloader.R
import io.github.orianbsilva.obsloader.emu.CoreInfo
import io.github.orianbsilva.obsloader.emu.Emulator
import io.github.orianbsilva.obsloader.emu.Native
import io.github.orianbsilva.obsloader.emu.Target
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Body = Color(0xFF2B2C30)
private val Well = Color(0xFF1C1D20)
private val Cap = Color(0xFF3A3B40)
private val Ink = Color(0xFFD9D6CF)
private val Dim = Color(0xFF8C8A85)
private val Led = Color(0xFFEEA139)

// panel.c's order: the button labels, and which encoder each knob is
private val BUTTONS = listOf("FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY\nSTOP", "REC")
private const val OCTDN = 12
private const val OCTUP = 13

private class Screen {
    val argb = IntArray(240 * 240)
    val lights = IntArray(4)
    val bitmap: Bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    var serial = -1
}

@Composable
fun EmuScreen(emu: Emulator, core: CoreInfo, close: () -> Unit) {
    val scr = remember { Screen() }
    var frame by remember { mutableIntStateOf(0) }
    var preset by remember { mutableStateOf("") }
    var mapping by remember { mutableStateOf(false) }
    LaunchedEffect(core) { emu.start(core) }
    // the screen and the lights, each time the firmware drew a frame
    LaunchedEffect(core) {
        var n = 0
        while (true) {
            withFrameNanos { }
            val s = Native.screen(scr.argb, scr.lights)
            if (s != scr.serial) {
                scr.serial = s
                scr.bitmap.setPixels(scr.argb, 0, 240, 0, 0, 240, 240)
                frame++
            }
            if (++n % 30 == 0) { preset = Native.presetName(); emu.saveFlash() }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> emu.pause()
                Lifecycle.Event.ON_START -> emu.resume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val lit = { b: Int -> (scr.lights[0] shr b) and 1 == 1 }
    val glow = { b: Int -> (scr.lights[2] shr b) and 1 == 1 }
    Column(Modifier.fillMaxSize().background(Color(0xFF141517)).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ emu.stop(); close() }) { Icon(Icons.Default.Close, stringResource(R.string.back), tint = Ink) }
            Column(Modifier.weight(1f)) {
                Text(core.name + if (preset.isNotEmpty()) " · $preset" else "", color = Ink, fontWeight = FontWeight.Bold, maxLines = 1)
                val ins = emu.inputs.values.joinToString(", ")
                Text(if (ins.isEmpty()) stringResource(R.string.emu_no_midi) else stringResource(R.string.emu_midi_in, ins),
                    color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            IconButton({ mapping = true }) { Icon(Icons.Default.Tune, stringResource(R.string.emu_cc_title), tint = if (emu.learning != null) Led else Ink) }
        }
        emu.learning?.let { t ->
            Text(stringResource(R.string.emu_learning, t.label), Modifier.fillMaxWidth().background(Led).clickable { emu.learning = null }.padding(8.dp),
                color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val keysH = if (wide) maxHeight * 0.36f else maxHeight * 0.26f
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Body).padding(8.dp)) {
                    if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        LeftBlock(emu)
                        Lcd(scr, frame, Modifier.fillMaxHeight().aspectRatio(1f))
                        RightBlock(emu, lit, glow)
                    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            MasterKnob(emu); EncKnob(emu, Target.SELECT); EncKnob(emu, Target.PRESETS); EncKnob(emu, Target.ALGORITHM)
                        }
                        Lcd(scr, frame, Modifier.weight(1f, false).widthIn(max = 320.dp).aspectRatio(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (t in listOf(Target.K1, Target.K2, Target.K3, Target.K4)) EncKnob(emu, t)
                        }
                        Buttons(lit, glow)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Keys(scr, frame, Modifier.fillMaxWidth().height(keysH))
            }
        }
    }
    if (mapping) CcDialog(emu) { mapping = false }
}

@Composable
private fun Lcd(scr: Screen, frame: Int, mod: Modifier) {
    Box(mod.clip(RoundedCornerShape(8.dp)).background(Color.Black).border(2.dp, Well, RoundedCornerShape(8.dp)).padding(4.dp)) {
        @Suppress("UNUSED_EXPRESSION") frame   // (read: a new frame redraws it)
        Image(scr.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    }
}

@Composable
private fun LeftBlock(emu: Emulator) {
    Column(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxHeight()) {
        Row { MasterKnob(emu); EncKnob(emu, Target.SELECT) }
        Row { EncKnob(emu, Target.PRESETS); EncKnob(emu, Target.ALGORITHM) }
    }
}

@Composable
private fun RightBlock(emu: Emulator, lit: (Int) -> Boolean, glow: (Int) -> Boolean) {
    Column(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (t in listOf(Target.K1, Target.K2, Target.K3, Target.K4)) EncKnob(emu, t)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelButton("OCT+", false, false, OCTUP, 50); PanelButton("OCT-", false, false, OCTDN, 50)
            }
        }
        Buttons(lit, glow, withOct = false)
    }
}

@Composable
private fun Buttons(lit: (Int) -> Boolean, glow: (Int) -> Boolean, withOct: Boolean = true) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Well).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 6) { val b = row * 6 + i; PanelButton(BUTTONS[b], lit(b), glow(b), b, 46) }
        }
        if (withOct) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("OCT-", false, false, OCTDN, 46); PanelButton("OCT+", false, false, OCTUP, 46)
        }
    }
}

@Composable
private fun PanelButton(label: String, lit: Boolean, glow: Boolean, id: Int, w: Int) {
    var down by remember { mutableStateOf(false) }
    Box(Modifier.size(w.dp, 34.dp).clip(RoundedCornerShape(6.dp)).background(if (down) Color(0xFF55565C) else Cap)
        .pointerInput(id) { detectTapGestures(onPress = { down = true; Native.btn(id, true); tryAwaitRelease(); Native.btn(id, false); down = false }) },
        contentAlignment = Alignment.Center) {
        Text(label, color = when { lit -> Led; glow -> Led.copy(alpha = 0.45f); else -> Ink }, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            lineHeight = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun KnobFace(label: String, angle: Float, learning: Boolean, mapped: Int?, mod: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(label, color = if (learning) Led else Dim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Canvas(mod.size(46.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF101113), r)
            drawCircle(if (learning) Led.copy(alpha = 0.35f) else Color(0xFF26272B), r * 0.82f)
            val a = (angle - 90f) * PI.toFloat() / 180f
            drawLine(Ink, center + Offset(cos(a), sin(a)) * (r * 0.25f), center + Offset(cos(a), sin(a)) * (r * 0.78f), 4f, StrokeCap.Round)
        }
        Text(mapped?.let { "CC $it" } ?: " ", color = Led.copy(alpha = 0.8f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
    }
}

/** an endless encoder: dragging up turns it right; one step every few pixels; in MIDI learn, a touch picks it */
@Composable
private fun EncKnob(emu: Emulator, t: Target) {
    var angle by remember { mutableStateOf(0f) }
    val mapped = emu.ccMap.entries.firstOrNull { it.value == t }?.key
    KnobFace(t.label, angle, emu.learning == t, mapped, Modifier.pointerInput(t) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (emu.learning != null) { emu.learning = t; return@awaitEachGesture }
            var acc = 0f
            val stepPx = 9.dp.toPx()
            var id: PointerId = down.id
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == id } ?: break
                if (!c.pressed) break
                acc += (c.previousPosition.y - c.position.y) + (c.position.x - c.previousPosition.x) * 0.5f
                val steps = (acc / stepPx).toInt()
                if (steps != 0) { acc -= steps * stepPx; Native.enc(t.role, steps); angle += steps * 12f }
                c.consume()
            }
        }
    })
}

/** MASTER: an absolute knob (the volume), 0..1023 */
@Composable
private fun MasterKnob(emu: Emulator) {
    val mapped = emu.ccMap.entries.firstOrNull { it.value == Target.MASTER }?.key
    KnobFace("MASTER", -135f + 270f * emu.master / 1023f, emu.learning == Target.MASTER, mapped, Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (emu.learning != null) { emu.learning = Target.MASTER; return@awaitEachGesture }
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                val d = (c.previousPosition.y - c.position.y) * 4f
                if (d != 0f) emu.turnMaster((emu.master + d).toInt())
                c.consume()
            }
        }
    })
}

// the FM-1's 27 keys, F3..G5: 16 white below, 11 black above (as its two rows of pads)
private val WHITE = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
private val BLACK = intArrayOf(1, 3, 5, 8, 10, 13, 15, 17, 20, 22, 25)
private fun blackSlot(n: Int): Float = WHITE.indexOfFirst { it > n } - 0.5f   // between its two whites

@Composable
private fun Keys(scr: Screen, frame: Int, mod: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val held = remember { mutableStateOf(setOf<Int>()) }
    fun keyAt(p: Offset): Int? {
        if (size.width == 0 || p.x < 0 || p.y < 0 || p.x >= size.width || p.y >= size.height) return null
        val ww = size.width / 16f
        if (p.y < size.height * 0.5f) BLACK.forEach { n ->
            val cx = (blackSlot(n) + 0.5f) * ww
            if (kotlin.math.abs(p.x - cx) < ww * 0.38f) return n
        }
        return WHITE[(p.x / ww).toInt().coerceIn(0, 15)]
    }
    Canvas(mod.clip(RoundedCornerShape(10.dp)).background(Well).onSizeChanged { size = it }.pointerInput(Unit) {
        val fingers = mutableMapOf<PointerId, Int>()
        fun sync() {
            val now = fingers.values.toSet()
            for (k in held.value - now) Native.key(k, false)
            for (k in now - held.value) Native.key(k, true)
            held.value = now
        }
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                for (c in ev.changes) {
                    if (c.pressed) { val k = keyAt(c.position); if (k != null) fingers[c.id] = k else fingers.remove(c.id) }
                    else fingers.remove(c.id)
                    c.consume()
                }
                sync()
            }
        }
    }) {
        @Suppress("UNUSED_EXPRESSION") frame
        val ww = size.width / 16f
        val keyLit = { n: Int -> (scr.lights[1] shr n) and 1 == 1 }
        val keyGlow = { n: Int -> (scr.lights[3] shr n) and 1 == 1 }
        fun pad(n: Int, x: Float, y: Float, w: Float, h: Float) {
            val on = n in held.value
            drawRoundRect(if (on) Color(0xFF5A5B61) else Cap, Offset(x + 3, y + 3), androidx.compose.ui.geometry.Size(w - 6, h - 6),
                androidx.compose.ui.geometry.CornerRadius(14f))
            val lc = when { keyLit(n) || on -> Led; keyGlow(n) -> Led.copy(alpha = 0.4f); else -> Led.copy(alpha = 0.12f) }
            drawLine(lc, Offset(x + w / 2, y + h * 0.2f), Offset(x + w / 2, y + h * 0.8f), 4f, StrokeCap.Round)
        }
        for ((i, n) in WHITE.withIndex()) pad(n, i * ww, size.height * 0.5f, ww, size.height * 0.5f)
        for (n in BLACK) pad(n, blackSlot(n) * ww + ww * 0.12f, 0f, ww * 0.76f, size.height * 0.5f)
    }
}

@Composable
private fun CcDialog(emu: Emulator, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.emu_cc_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.emu_cc_help), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            for (t in Target.entries) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val cc = emu.ccMap.entries.firstOrNull { it.value == t }?.key
                Text(t.label, Modifier.width(96.dp), fontWeight = FontWeight.Bold)
                Text(cc?.let { "CC $it" } ?: "—", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton({ emu.learning = t; close() }) { Text(stringResource(R.string.emu_learn)) }
                if (cc != null) TextButton({ emu.unmap(cc) }) { Text("×") }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.emu_erase_help), style = MaterialTheme.typography.labelSmall)
            TextButton({ emu.core?.let { emu.erase(it) }; close() }) { Text(stringResource(R.string.emu_erase), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(close) { Text("OK") } })
}
