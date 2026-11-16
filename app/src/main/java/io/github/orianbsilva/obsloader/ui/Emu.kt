// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. The emulated FM-1 on the phone's screen: the firmware's own screen, the knobs (drag
// up and down; MIDI learn gives one to a controller's CC), the buttons with their LEDs, OCT and the 27 keys (several
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.orianbsilva.obsloader.emu.EmuPackage
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

// the buttons' labels, by the FM-1's matrix id (2..13); OCT- is 0, OCT+ 1, the keys 14..40
private val BUTTONS = listOf("FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY\nSTOP", "REC")
private const val OCTDN = 0
private const val OCTUP = 1
private const val KEY0 = 14

private class Screen {
    val argb = IntArray(240 * 240)
    val leds = FloatArray(41)
    val bitmap: Bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    var serial = -1
}

@Composable
fun EmuScreen(emu: Emulator, pkg: EmuPackage, newerChip: Boolean, close: () -> Unit) {
    val scr = remember { Screen() }
    var frame by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var mapping by remember { mutableStateOf(false) }
    var keysOn by remember { mutableStateOf(emu.keysShown) }
    LaunchedEffect(pkg.id) { emu.start(pkg, newerChip) }
    // the screen and the lights, each time the firmware drew a frame
    LaunchedEffect(pkg.id) {
        var n = 0
        while (true) {
            withFrameNanos { }
            val s = Native.screen(scr.argb, scr.leds)
            if (s != scr.serial) {
                scr.serial = s
                scr.bitmap.setPixels(scr.argb, 0, 240, 0, 0, 240, 240)
                frame++
            }
            if (++n % 20 == 0) {
                emu.saveFlash()
                status = when {
                    Native.halted() -> "halted"
                    Native.late() > 0 -> "late ${Native.late()}"
                    else -> ""
                }
            }
        }
    }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val was = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val win = activity?.window
        val bars = win?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (was != null) activity.requestedOrientation = was
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
    @Suppress("UNUSED_EXPRESSION") frame
    val led = { id: Int -> scr.leds[id] }
    Column(Modifier.fillMaxSize().background(Color(0xFF141517)).displayCutoutPadding()) {
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ emu.stop(); close() }) { Icon(Icons.Default.Close, stringResource(R.string.back), tint = Ink) }
            Text(pkg.name + " " + pkg.version, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            val ins = emu.inputs.values.joinToString(", ")
            Text(when {
                status == "halted" -> stringResource(R.string.emu_halted)
                emu.failed != null -> emu.failed!!
                ins.isEmpty() -> stringResource(R.string.emu_no_midi)
                else -> stringResource(R.string.emu_midi_in, ins)
            }, Modifier.weight(1f), color = if (status == "halted" || emu.failed != null) Led else Dim,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton({ keysOn = !keysOn; emu.keysShown = keysOn }) {
                Icon(Icons.Default.Piano, stringResource(R.string.emu_keys), tint = if (keysOn) Led else Ink)
            }
            IconButton({ mapping = true }) { Icon(Icons.Default.Tune, stringResource(R.string.emu_cc_title), tint = if (emu.learning != null) Led else Ink) }
        }
        emu.learning?.let { t ->
            Text(stringResource(R.string.emu_learning, t.label), Modifier.fillMaxWidth().background(Led).clickable { emu.learning = null }.padding(8.dp),
                color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
        // always sideways, as the FM-1 lies: the screen on the left, the 8 knobs and the 12 buttons on the right,
        // the keys below (or away: a MIDI keyboard plays, and the panel takes the room)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp)) {
            val keysH = maxHeight * 0.34f
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Body).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Lcd(scr, frame, Modifier.fillMaxHeight().aspectRatio(1f))
                    ButtonsBlock(led, Modifier.weight(1f).fillMaxHeight())
                    KnobsBlock(emu, Modifier.fillMaxHeight())
                }
                if (keysOn) {
                    Spacer(Modifier.height(10.dp))
                    Keys(scr, frame, led, Modifier.fillMaxWidth().height(keysH))
                }
            }
        }
    }
    if (mapping) CcDialog(emu, newerChip) { mapping = false }
}

@Composable
private fun Lcd(scr: Screen, frame: Int, mod: Modifier) {
    Box(mod.clip(RoundedCornerShape(8.dp)).background(Color.Black).border(2.dp, Well, RoundedCornerShape(8.dp)).padding(4.dp)) {
        @Suppress("UNUSED_EXPRESSION") frame   // (read: a new frame redraws it)
        Image(scr.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    }
}




private val KnobSize = androidx.compose.runtime.compositionLocalOf { 46 }
private val ButtonH = androidx.compose.runtime.compositionLocalOf { 34 }

/** the middle: the 12 buttons, three rows of four, and OCT- / OCT+ under them; sized from the room there is */
@Composable
private fun ButtonsBlock(led: (Int) -> Float, mod: Modifier) {
    BoxWithConstraints(mod) {
        val gap = 8.dp
        val w = (maxWidth - gap * 3) / 4
        val h = minOf((maxHeight - gap * 4) / 4.6f, w * 0.75f).coerceAtLeast(24.dp)
        CompositionLocalProvider(ButtonH provides h.value.toInt()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                for (row in 0 until 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (i in 0 until 4) { val b = row * 4 + i; PanelButton(BUTTONS[b], led(b + 2), b + 2, w.value.toInt(), h.value.toInt()) }
                }
                Spacer(Modifier.height(gap / 2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    PanelButton("OCT-", led(OCTDN), OCTDN, w.value.toInt(), (h * 0.8f).value.toInt())
                    PanelButton("OCT+", led(OCTUP), OCTUP, w.value.toInt(), (h * 0.8f).value.toInt())
                }
            }
        }
    }
}

/** the right: the 8 knobs, two by two (MASTER SELECT, PRESETS ALGORITHM, KNOB1 KNOB2, KNOB3 KNOB4) */
@Composable
private fun KnobsBlock(emu: Emulator, mod: Modifier) {
    BoxWithConstraints(mod) {
        val knob = ((maxHeight - 4.dp * 3) / 4 - 26.dp).coerceIn(24.dp, 72.dp)   // (each row: its label, the knob, room for "CC n")
        CompositionLocalProvider(KnobSize provides knob.value.toInt()) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MasterKnob(emu); EncKnob(emu, Target.SELECT) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { EncKnob(emu, Target.PRESETS); EncKnob(emu, Target.ALGORITHM) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { EncKnob(emu, Target.K1); EncKnob(emu, Target.K2) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { EncKnob(emu, Target.K3); EncKnob(emu, Target.K4) }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, led: Float, id: Int, w: Int, h: Int) {
    var down by remember { mutableStateOf(false) }
    // the LED tints the cap, as on the FM-1
    val cap = androidx.compose.ui.graphics.lerp(if (down) Color(0xFF55565C) else Cap, Led, (led * 0.75f).coerceIn(0f, 0.75f))
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(cap)
        .pointerInput(id) { detectTapGestures(onPress = { down = true; Native.key(id, true); tryAwaitRelease(); Native.key(id, false); down = false }) },
        contentAlignment = Alignment.Center) {
        Text(label, color = if (led > 0.5f) Color(0xFF2A1A03) else Ink, fontSize = (minOf(h / 3.2f, w / 4.6f)).sp, fontWeight = FontWeight.Bold,
            lineHeight = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun KnobFace(label: String, angle: Float, learning: Boolean, mapped: Int?, mod: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 64.dp)) {
        Text(label, color = if (learning) Led else Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Canvas(mod.size(KnobSize.current.dp)) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF101113), r)
            drawCircle(if (learning) Led.copy(alpha = 0.35f) else Color(0xFF26272B), r * 0.82f)
            val a = (angle - 90f) * PI.toFloat() / 180f
            drawLine(Ink, center + Offset(cos(a), sin(a)) * (r * 0.25f), center + Offset(cos(a), sin(a)) * (r * 0.78f), 4f, StrokeCap.Round)
        }
        if (mapped != null) Text("CC $mapped", color = Led.copy(alpha = 0.8f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
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
private fun Keys(scr: Screen, frame: Int, led: (Int) -> Float, mod: Modifier) {
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
            for (k in held.value - now) Native.key(KEY0 + k, false)
            for (k in now - held.value) Native.key(KEY0 + k, true)
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
        val keyLed = { n: Int -> scr.leds[KEY0 + n] }
        fun pad(n: Int, x: Float, y: Float, w: Float, h: Float) {
            val on = n in held.value
            drawRoundRect(if (on) Color(0xFF5A5B61) else Cap, Offset(x + 3, y + 3), androidx.compose.ui.geometry.Size(w - 6, h - 6),
                androidx.compose.ui.geometry.CornerRadius(14f))
            val lc = if (on) Led else Led.copy(alpha = (0.12f + 0.88f * keyLed(n)).coerceIn(0f, 1f))
            drawLine(lc, Offset(x + w / 2, y + h * 0.2f), Offset(x + w / 2, y + h * 0.8f), 4f, StrokeCap.Round)
        }
        for ((i, n) in WHITE.withIndex()) pad(n, i * ww, size.height * 0.5f, ww, size.height * 0.5f)
        for (n in BLACK) pad(n, blackSlot(n) * ww + ww * 0.12f, 0f, ww * 0.76f, size.height * 0.5f)
    }
}

@Composable
private fun CcDialog(emu: Emulator, newerChip: Boolean, close: () -> Unit) {
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
            TextButton({ emu.running?.let { emu.erase(it, newerChip) }; close() }) { Text(stringResource(R.string.emu_erase), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(close) { Text("OK") } })
}
