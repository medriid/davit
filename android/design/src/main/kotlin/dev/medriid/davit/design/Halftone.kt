package dev.medriid.davit.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A tiled dot brush: one cell holding one antialiased dot, repeated in both directions.
 * [dotRatio] is the dot radius as a fraction of the cell. The CSS radial gradients in
 * tokens.css work out to about 0.28 for display text and 0.27 for fills.
 */
@Composable
fun rememberHalftoneBrush(color: Color, cell: Dp = 8.dp, dotRatio: Float = 0.27f): ShaderBrush {
    val cellPx = with(LocalDensity.current) { cell.toPx().roundToInt().coerceAtLeast(2) }
    return remember(color, cellPx, dotRatio) { halftoneBrush(color, cellPx, dotRatio) }
}

fun halftoneBrush(color: Color, cellPx: Int, dotRatio: Float): ShaderBrush {
    val tile = ImageBitmap(cellPx, cellPx)
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = true
    }
    Canvas(tile).drawCircle(Offset(cellPx / 2f, cellPx / 2f), cellPx * dotRatio, paint)
    return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/** Fills the element with ink-B dots, multiplied onto whatever is below. */
@Composable
fun Modifier.halftone(color: Color = LocalInks.current.b, cell: Dp = 8.dp): Modifier {
    val brush = rememberHalftoneBrush(color, cell)
    return drawBehind { drawRect(brush, blendMode = BlendMode.Multiply) }
}

/**
 * Attendance-style bar. Safe: a Teal halftone fill. At risk: a solid ink-A fill with the
 * ink-B halftone overprinted a little out of register. A threshold tick sits at [threshold].
 * The fill springs to new values, so a changed percentage is visible without reading it.
 */
@Composable
fun HalftoneBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    threshold: Float? = null,
    atRisk: Boolean = threshold != null && fraction < threshold,
    height: Dp = 14.dp,
) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (motion) RisoMotion.quick() else snap(),
        label = "bar",
    )
    val dots = rememberHalftoneBrush(inks.b, cell = 6.dp, dotRatio = 0.3f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val fillW = size.width * shown
                // track
                drawRect(inks.rule, style = Stroke(stroke))
                if (atRisk) {
                    drawRect(inks.a, size = Size(fillW, size.height), blendMode = BlendMode.Multiply)
                    val shift = 2.dp.toPx()
                    drawRect(
                        dots,
                        topLeft = Offset(shift, shift * 0.6f),
                        size = Size((fillW - shift).coerceAtLeast(0f), size.height - shift * 0.6f),
                        blendMode = BlendMode.Multiply,
                    )
                } else {
                    drawRect(dots, size = Size(fillW, size.height), blendMode = BlendMode.Multiply)
                }
                if (threshold != null) {
                    val x = size.width * threshold
                    val over = 4.dp.toPx()
                    drawRect(
                        inks.text,
                        topLeft = Offset(x - stroke, -over),
                        size = Size(stroke * 2, size.height + over * 2),
                    )
                }
            },
    )
}
