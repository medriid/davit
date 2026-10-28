package dev.medriid.davit.design

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Prints this element like ink: it's drawn into its own layer, and the layer is
 * multiplied onto everything already on the sheet (`mix-blend-mode: multiply`).
 *
 * Compose 1.7's `graphicsLayer` has no blend mode, so this uses a canvas `saveLayer`
 * with a Multiply paint, which works on every API level. [bleed] grows the layer so
 * glyphs that overhang their line box, or a plate shifted out of register, aren't cut.
 */
fun Modifier.multiply(bleed: Dp = 48.dp): Modifier = composed {
    // Multiply on paper, screen at night (see [Inks.blend]).
    val blend = LocalInks.current.blend
    val paint = remember(blend) { Paint().apply { blendMode = blend } }
    drawWithContent {
        val pad = bleed.toPx()
        val bounds = Rect(-pad, -pad, size.width + pad, size.height + pad)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, paint)
            drawContent()
            canvas.restore()
        }
    }
}
