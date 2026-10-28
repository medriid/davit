package dev.medriid.davit.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The one callout per screen (`.offer`): an ink-A block turned -1.6 degrees, with a
 * halftone circle bleeding off the bottom-right corner. Content inside is meant to be
 * ink B; [LocalOnCard] tells text helpers they sit on the card.
 */
@Composable
fun TiltedCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val inks = LocalInks.current
    val dots = rememberHalftoneBrush(inks.b, cell = 9.dp, dotRatio = 0.23f)
    Box(
        modifier
            .graphicsLayer { rotationZ = -1.6f }
            .background(inks.a)
            .drawWithContent {
                val r = 60.dp.toPx()
                val bleed = 16.dp.toPx()
                clipRect {
                    drawCircle(
                        dots,
                        radius = r,
                        center = Offset(size.width + bleed - r, size.height + bleed - r),
                        blendMode = inks.blend,
                    )
                }
                drawContent()
            }
            .padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 20.dp),
    ) {
        CompositionLocalProvider(LocalOnCard provides true) { content() }
    }
}

/** True inside a [TiltedCard], where text should be ink B rather than the text pull. */
val LocalOnCard = staticCompositionLocalOf { false }
