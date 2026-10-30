package dev.medriid.davit.design

import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Primary button (`.btn`): an ink-B block with an ink-A plate peeking out behind it.
 * Pressing flips the plate to the other side and lifts the block a touch, as the web
 * hover does. [quiet] swaps to a paper block with an ink outline, for secondary actions.
 * At least 54 dp tall: buttons are for the actions that matter, so they're easy to hit.
 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
    fillWidth: Boolean = false,
) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val plateTarget = if (pressed) DpOffset((-3).dp, 5.dp) else DpOffset(4.dp, 3.dp)
    val blockTarget = if (pressed) DpOffset(1.dp, (-1).dp) else DpOffset(0.dp, 0.dp)
    val plate by animateValueAsState(
        plateTarget, DpOffset.VectorConverter,
        if (motion) RisoMotion.quick() else snap(), label = "plate",
    )
    val block by animateValueAsState(
        blockTarget, DpOffset.VectorConverter,
        if (motion) RisoMotion.quick() else snap(), label = "block",
    )

    // Riso: an ink-B block on an ink-A plate. Sticker print: an ink-A block with black type on a black shadow.
    // Glass: a solid black (or white) block, or frosted glass when quiet, on a soft shadow.
    val blockColor = when {
        quiet -> if (inks.glass) inks.glassFill else inks.paper
        inks.sticker || inks.glass -> inks.a
        else -> inks.b
    }
    val textColor = if (quiet || inks.sticker) inks.text else inks.paper
    val plateColor = if (inks.glass) inks.glassShadow else if (inks.sticker) inks.text else inks.a
    val shape = if (inks.glass) GlassShape else CardShape
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .offset { IntOffset(plate.x.roundToPx(), plate.y.roundToPx()) }
                .clip(shape)
                .background(if (enabled) plateColor else inks.rule),
        )
        Box(
            Modifier
                .offset { IntOffset(block.x.roundToPx(), block.y.roundToPx()) }
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .clip(shape)
                .background(if (enabled) blockColor else inks.paper)
                .then(
                    when {
                        inks.glass -> if (quiet || !enabled) Modifier.border(1.dp, inks.glassEdge, shape) else Modifier
                        quiet || !enabled || inks.sticker -> Modifier.border(2.dp, inks.text, CardShape)
                        else -> Modifier
                    },
                )
                .defaultMinSize(minHeight = 54.dp)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text.uppercase(),
                style = RisoType.button.copy(color = if (enabled) textColor else inks.rule),
            )
        }
    }
}
