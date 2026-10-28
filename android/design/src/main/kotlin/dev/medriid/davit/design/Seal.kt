package dev.medriid.davit.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app mark, drawn from the launcher icon (`res/drawable/ic_launcher_foreground.xml`):
 * an ink-A block, a paper frame, a paper "D" and a halftone ramp in the text ink. It
 * recolours with the ink pair. Use it as the logo only, at most once per screen.
 */
@Composable
fun Seal(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    description: String = "daVIT",
) {
    val inks = LocalInks.current
    val corner = 3.dp * (size / 44.dp)
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        // The icon's 108-unit viewport, cropped to the frame and the whole dot ramp.
        val u = this.size.minDimension / Window
        fun p(x: Float, y: Float) = Offset((x - Origin) * u, (y - Origin) * u)

        drawRoundRect(inks.a, cornerRadius = CornerRadius(corner.toPx()))
        Dots.forEach { (x, y, r) -> drawCircle(inks.text, r * u, p(x, y), blendMode = BlendMode.Multiply) }
        drawRect(inks.paper, p(31f, 31f), Size(46 * u, 46 * u), style = Stroke(3 * u))
        val d = Path().apply {
            p(42f, 40f).let { moveTo(it.x, it.y) }
            p(42f, 68f).let { lineTo(it.x, it.y) }
            p(42f, 40f).let { moveTo(it.x, it.y) }
            p(52f, 40f).let { lineTo(it.x, it.y) }
            arcTo(Rect(p(38f, 40f), p(66f, 68f)), -90f, 180f, false)
            p(42f, 68f).let { lineTo(it.x, it.y) }
        }
        drawPath(d, inks.paper, style = Stroke(5 * u, cap = StrokeCap.Square))
    }
}

private const val Origin = 26f
private const val Window = 56f

/** The launcher's halftone ramp: centre x, centre y, radius, in icon units. */
private val Dots = listOf(
    Triple(68f, 54f, 0.71f), Triple(75f, 54f, 0.88f),
    Triple(57.5f, 61f, 0.64f), Triple(64.5f, 61f, 0.99f), Triple(71.5f, 61f, 1.26f), Triple(78.5f, 61f, 1.41f),
    Triple(54f, 68f, 0.71f), Triple(61f, 68f, 1.13f), Triple(68f, 68f, 1.51f), Triple(75f, 68f, 1.80f),
    Triple(50.5f, 75f, 0.64f), Triple(57.5f, 75f, 1.12f), Triple(64.5f, 75f, 1.58f), Triple(71.5f, 75f, 2.02f), Triple(78.5f, 75f, 2.35f),
)
