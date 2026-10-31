package dev.medriid.davit.design

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(10.dp)

/** Glass cards are rounder. */
val GlassShape = RoundedCornerShape(18.dp)

/** Soft sheets and cards: generously rounded. */
val SoftShape = RoundedCornerShape(24.dp)

/** Editorial tiles: all but square. */
val EditorialShape = RoundedCornerShape(3.dp)

/**
 * The workhorse surface: paper with a 2 dp ink border and a solid ink-A plate printed
 * [plateOffset] down-right behind it. The plate space is reserved, so cards never
 * overlap their neighbours.
 *
 * With [onClick], the whole card is the tap target; pressing pushes the card down onto
 * its plate, so it feels like a button.
 *
 * A [kind] prints the plate in that kind's ink and washes the paper with it, so a class
 * card and an exam card look different at a glance. [fill] and [plate] still win.
 */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    kind: Kind? = null,
    fill: Color? = null,
    plate: Color? = null,
    plateOffset: Dp = 5.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val inks = LocalInks.current
    if (inks.editorial) {
        // Editorial: a flat tile, nearly square, a hairline edge; a flagged card (its own plate)
        // gets a red bar down its left. Tappable ones sink a little under your finger.
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val sink by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.975f else 1f, RisoMotion.quick(), label = "sink")
        Column(
            modifier
                .graphicsLayer { scaleX = sink; scaleY = sink }
                .clip(EditorialShape)
                .background(fill ?: inks.fill(kind))
                .border(1.dp, inks.rule, EditorialShape)
                .then(
                    if (plate != null) Modifier.drawBehind { drawRect(inks.a, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
                    else Modifier,
                )
                .then(if (onClick != null) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
        return
    }
    if (inks.soft) {
        // Soft: a rounded sheet, no border and no shadow; a flagged card (its own plate) gets a coral edge.
        Column(
            modifier
                .clip(SoftShape)
                .background(fill ?: inks.fill(kind))
                .then(if (plate != null) Modifier.border(2.dp, inks.a, SoftShape) else Modifier)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(contentPadding)
                .padding(2.dp),
            content = content,
        )
        return
    }
    // Glass always casts the same soft shadow (a coloured one would show through the card);
    // a card flagged with its own plate gets a bright edge instead.
    val plateColor = if (inks.glass) inks.glassShadow else plate ?: inks.plate(kind)
    // Glass: rounder, with a fine light edge instead of the ink border.
    val shape = if (inks.glass) GlassShape else CardShape
    val fillColor = fill ?: inks.fill(kind)
    val motion = rememberMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val push by animateDpAsState(
        if (pressed) plateOffset * 0.7f else 0.dp,
        if (motion) RisoMotion.quick() else snap(),
        label = "push",
    )

    // Min sizes pass through, so a caller's fillMaxHeight (equal-height rows) still fills; otherwise it wraps.
    Box(modifier.padding(end = plateOffset, bottom = plateOffset), propagateMinConstraints = true) {
        Box(
            Modifier
                .matchParentSize()
                .offset(plateOffset, plateOffset)
                .clip(shape)
                .background(plateColor),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(push.roundToPx(), push.roundToPx()) }
                .clip(shape)
                .background(fillColor)
                .border(
                    if (inks.glass && plate == null) 1.dp else 2.dp,
                    if (!inks.glass) inks.text else if (plate != null) inks.a else inks.glassEdge,
                    shape,
                )
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/** Small bordered tag: room, slot, "6 days left". [solid] reverses it out of ink B. */
@Composable
fun InkChip(text: String, modifier: Modifier = Modifier, solid: Boolean = false) {
    val inks = LocalInks.current
    val shape = if (inks.soft) RoundedCornerShape(50) else if (inks.editorial) EditorialShape else RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (solid) (if (inks.soft || inks.editorial) inks.text else if (inks.sticker || inks.glass) inks.a else inks.b) else Color.Transparent)
            .border(if (inks.soft || inks.editorial) 1.dp else 1.5.dp, if (inks.soft || inks.editorial) (if (solid) inks.text else inks.text.copy(alpha = 0.3f)) else if (inks.glass) (if (solid) inks.a else inks.rule) else if (solid && !inks.sticker) inks.b else inks.text, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        BasicText(
            text,
            maxLines = 1,
            softWrap = false,
            style = RisoType.label.copy(
                color = if (solid && !inks.sticker) inks.paper else inks.text,
                letterSpacing = RisoType.label.letterSpacing * 0.5f,
            ),
        )
    }
}
