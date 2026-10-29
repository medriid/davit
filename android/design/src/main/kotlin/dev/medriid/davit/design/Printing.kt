package dev.medriid.davit.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * List entrance: the [index]th item prints in after a short stagger, rising a few dp and
 * landing a touch out of register, then settling. Only on first composition; with
 * animations off it's simply there.
 */
fun Modifier.printIn(index: Int): Modifier = composed {
    val motion = rememberMotionEnabled()
    val t = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        delay(index.coerceIn(0, 10) * 38L)
        t.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 320f))
    }
    graphicsLayer {
        val v = t.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 18.dp.toPx()
        translationX = (1f - v) * 5.dp.toPx()
    }
}

/** Haptics through the view, so they follow the phone's own touch-feedback setting. */
class Haptics(private val view: View) {
    /** Light: a tab, a chip, a toggle. */
    fun tick() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_RELEASE else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Something went through: uploaded, downloaded, applied. */
    fun confirm() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** VTOP said no, or a check failed. */
    fun reject() {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    /** A weighty press: the upload's confirm, a pull that triggers a sync. */
    fun heavy() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * The pull-to-refresh drum: an ink roller that drops in with the pull (turning as it
 * comes), and while refreshing keeps rolling and lays an ink stripe across the top that
 * runs like a progress bar. [pull] is 0 at rest and 1 at the trigger point.
 */
@Composable
fun DrumIndicator(pull: Float, refreshing: Boolean, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val dots = rememberHalftoneBrush(inks.b, cell = 5.dp, dotRatio = 0.3f)
    // The loops only run while refreshing (and only with animations on), and are read in
    // the draw step, so a turning drum redraws without recomposing.
    val turnState = loop(motion && refreshing, 900, "turn")
    val runState = loop(motion && refreshing, 1300, "run")
    val shown = if (refreshing) 1f else pull.coerceIn(0f, 1.4f)
    if (shown <= 0.01f && !refreshing) return
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val turn = turnState.value
        val run = runState.value
        val w = 58.dp.toPx()
        val h = 24.dp.toPx()
        val top = -h + (h + 18.dp.toPx()) * shown.coerceAtMost(1f) + (shown - 1f).coerceAtLeast(0f) * 10.dp.toPx()
        val left = (size.width - w) / 2
        // The stripe the drum leaves behind: ink A, with a halftone run while it works.
        if (refreshing) {
            val stripe = 4.dp.toPx()
            drawRect(inks.a, Offset(0f, 0f), Size(size.width, stripe), blendMode = inks.blend)
            val seg = size.width * 0.28f
            val x = -seg + (size.width + seg) * (if (motion) run else 0.5f)
            drawRect(dots, Offset(x, 0f), Size(seg, stripe + 2.dp.toPx()), blendMode = inks.blend)
        }
        translate(left, top) {
            val r = CornerRadius(h / 2)
            drawRoundRect(inks.paper, size = Size(w, h), cornerRadius = r)
            val body = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, r)) }
            clipPath(body) {
                // Bands across the roller move with the pull, then with time: it reads as turning.
                val band = 9.dp.toPx()
                val phase = ((if (refreshing && motion) turn else shown * 1.6f) % 1f) * band * 2
                var x = -band * 2 + phase
                while (x < w + band) {
                    drawRect(inks.a, Offset(x, 0f), Size(band, h), blendMode = inks.blend)
                    drawRect(dots, Offset(x + band, 0f), Size(band, h), blendMode = inks.blend)
                    x += band * 2
                }
            }
            drawRoundRect(inks.text, size = Size(w, h), cornerRadius = r, style = Stroke(2.dp.toPx()))
            // Axle stubs.
            drawLine(inks.text, Offset(-6.dp.toPx(), h / 2), Offset(0f, h / 2), 2.5.dp.toPx())
            drawLine(inks.text, Offset(w, h / 2), Offset(w + 6.dp.toPx(), h / 2), 2.5.dp.toPx())
        }
    }
}

/**
 * Loading placeholder: halftone bars whose dots pulse in a wave, instead of grey
 * skeletons. Static with animations off.
 */
@Composable
fun LoadingPlate(modifier: Modifier = Modifier, lines: Int = 3) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val dots = rememberHalftoneBrush(inks.b, cell = 6.dp, dotRatio = 0.3f)
    val wave = loop(motion, 1100, "wave")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            val width = listOf(1f, 0.72f, 0.86f, 0.6f)[i % 4]
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(14.dp)
                    .drawBehind {
                        val phase = (wave.value + i * 0.18f) % 1f
                        drawRect(inks.rule.copy(alpha = 0.18f))
                        val band = size.width * 0.35f
                        drawRect(dots, Offset(-band + (size.width + band) * phase, 0f), Size(band, size.height), blendMode = inks.blend)
                    },
            )
        }
    }
}

/**
 * The first sync's "printing" sheet: each stage is a plate that lands as it finishes, ink A
 * first, then the text plate springs into register. The stage under way pulses as halftone.
 */
@Composable
fun PrintingSheet(stages: List<String>, current: Int, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stages.forEachIndexed { i, stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        i < current -> PrintedTick(i)
                        i == current -> LoadingDot()
                        else -> Canvas(Modifier.size(10.dp)) { drawCircle(inks.rule, size.minDimension / 2, style = Stroke(1.5.dp.toPx())) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (i <= current) {
                    Box(Modifier.printIn(0)) {
                        InkText(stage, style = RisoType.body.copy(fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal))
                    }
                } else {
                    InkText(stage, style = RisoType.body.copy(color = inks.rule))
                }
            }
        }
    }
}

@Composable
private fun PrintedTick(seed: Int) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val s = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) s.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
    Canvas(Modifier.size(20.dp).graphicsLayer { scaleX = s.value; scaleY = s.value; rotationZ = (seed % 3 - 1) * 4f }) {
        drawCircle(inks.a, size.minDimension / 2, blendMode = inks.blend)
        val p = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.34f)
        }
        drawPath(p, inks.paper, style = Stroke(2.4.dp.toPx()))
    }
}

@Composable
private fun LoadingDot(size: Dp = 14.dp) {
    val inks = LocalInks.current
    val pulse = loop(rememberMotionEnabled(), 1040, "dot")
    Canvas(Modifier.size(size)) {
        // 0 → 1 → 0 over one loop: the dot breathes between 55% and full size.
        val p = 0.55f + 0.45f * (1f - kotlin.math.abs(pulse.value * 2f - 1f))
        drawCircle(inks.b, this.size.minDimension / 2 * p, blendMode = inks.blend)
    }
}

/**
 * 0 → 1, over and over, every [millis] while [on]; a still 0.5 otherwise, so nothing
 * redraws with animations off. Read `.value` in a draw step to skip recomposition.
 */
@Composable
fun loop(on: Boolean, millis: Int, label: String): androidx.compose.runtime.State<Float> {
    if (!on) return remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    val t = rememberInfiniteTransition(label = label)
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = LinearEasing), RepeatMode.Restart), label = label)
}
