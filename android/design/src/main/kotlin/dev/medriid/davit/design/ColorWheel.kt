package dev.medriid.davit.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val hueStops = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }

/**
 * A hue and saturation wheel: round the rim is the hue, out from the middle the
 * saturation, shaded by [value] (set with [ValueSlider]). Drag anywhere on it; [onChange]
 * gets every move and [onEnd] the lift. The thumb shows the colour it's on.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
    onEnd: () -> Unit = {},
) {
    val inks = LocalInks.current
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = "Colour wheel, hue ${hue.roundToInt()} degrees, saturation ${(saturation * 100).roundToInt()} percent" }
            .pointerInput(Unit) {
                drag({ p ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.width / 2f - 14.dp.toPx()
                    val d = p - c
                    val angle = Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat()
                    change((angle + 360f) % 360f, (hypot(d.x, d.y) / r).coerceIn(0f, 1f))
                }, { end() })
            },
    ) {
        val r = size.minDimension / 2f - 14.dp.toPx()
        val c = center
        drawCircle(Brush.sweepGradient(hueStops, c), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
        drawCircle(Color.Black.copy(alpha = 1f - value), r, c)
        drawCircle(inks.text, r, c, style = Stroke(2.dp.toPx()))
        // The thumb: the colour itself in a paper ring, outlined in ink.
        val a = Math.toRadians(hue.toDouble())
        val t = Offset(c.x + (cos(a) * saturation * r).toFloat(), c.y + (sin(a) * saturation * r).toFloat())
        drawCircle(hsvColor(hue, saturation, value), 12.dp.toPx(), t)
        drawCircle(Color.White, 12.dp.toPx(), t, style = Stroke(3.dp.toPx()))
        drawCircle(inks.text, 14.dp.toPx(), t, style = Stroke(2.dp.toPx()))
    }
}

/** Brightness, from black on the left to the full colour on the right. */
@Composable
fun ValueSlider(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onEnd: () -> Unit = {},
) {
    val inks = LocalInks.current
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = "Brightness, ${(value * 100).roundToInt()} percent" }
            .pointerInput(Unit) {
                drag({ p ->
                    val inset = 14.dp.toPx()
                    change(((p.x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f))
                }, { end() })
            },
    ) {
        val inset = 14.dp.toPx()
        val barH = 18.dp.toPx()
        val top = (size.height - barH) / 2f
        val corner = CornerRadius(barH / 2f)
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color.Black, hsvColor(hue, saturation, 1f)), startX = inset, endX = size.width - inset),
            Offset(0f, top), Size(size.width, barH), corner,
        )
        drawRoundRect(inks.text, Offset(0f, top), Size(size.width, barH), corner, style = Stroke(2.dp.toPx()))
        val x = inset + value * (size.width - 2 * inset)
        val t = Offset(x, size.height / 2f)
        drawCircle(hsvColor(hue, saturation, value), 12.dp.toPx(), t)
        drawCircle(Color.White, 12.dp.toPx(), t, style = Stroke(3.dp.toPx()))
        drawCircle(inks.text, 14.dp.toPx(), t, style = Stroke(2.dp.toPx()))
    }
}

/**
 * Follows one finger from the moment it lands, taking the touch for itself so the screen
 * doesn't scroll while you're choosing a colour.
 */
private suspend fun PointerInputScope.drag(at: (Offset) -> Unit, done: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        at(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            change.consume()
            at(change.position)
        }
        done()
    }
}
