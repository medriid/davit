package dev.medriid.davit.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.dp

/** Section divider (`.rule`): a 2 dp line over a 1 dp line, 6 dp apart overall. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    if (LocalInks.current.editorial) {
        // Editorial: one firm rule in the text colour, as a newspaper sets its sections.
        Spacer(modifier.fillMaxWidth().height(2.dp).background(LocalInks.current.text))
        return
    }
    if (LocalInks.current.soft) {
        // Soft: one hairline.
        Spacer(modifier.fillMaxWidth().height(1.dp).background(LocalInks.current.rule))
        return
    }
    val ink = LocalInks.current.b
    val blend = LocalInks.current.blend
    Spacer(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawBehind {
                val thick = 2.dp.toPx()
                val thin = 1.dp.toPx()
                drawRect(ink, size = Size(size.width, thick), blendMode = blend)
                drawRect(
                    ink,
                    topLeft = Offset(0f, size.height - thin),
                    size = Size(size.width, thin),
                    blendMode = blend,
                )
            },
    )
}
