package dev.medriid.davit.ui

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType

/**
 * Classic or Editorial, side by side, each as a small drawing of how its Home looks (in
 * light or dark, as you have it). The app switches the moment you tap one.
 */
@Composable
internal fun StylePicker(editorial: Boolean, onPick: (Boolean) -> Unit) {
    val dark = LocalInks.current.dark
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StyleOption(
            "Classic", "Printed cards in the colours you pick", selected = !editorial,
            modifier = Modifier.weight(1f), onClick = { onPick(false) },
        ) { drawClassic(InkPairs.of(0, dark)) }
        StyleOption(
            "Editorial", "A Swiss grid of big numbers and live tiles", selected = editorial,
            modifier = Modifier.weight(1f), onClick = { onPick(true) },
        ) { drawEditorial(InkPairs.of(InkPairs.editorialIndex, dark)) }
    }
}

@Composable
private fun StyleOption(name: String, line: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    val inks = LocalInks.current
    val shape = RoundedCornerShape(if (inks.editorial) 3.dp else 12.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) inks.text else inks.rule, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$name style. $line."
            }
            .padding(10.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(6.dp)), onDraw = draw)
        Spacer(Modifier.height(8.dp))
        BasicText(name, style = RisoType.title.copy(color = inks.text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        BasicText(line, style = RisoType.small.copy(color = inks.muted, fontSize = 12.sp, lineHeight = 15.sp), maxLines = 3)
    }
}

/** Classic Home in miniature: two pastel cards with black outlines and hard shadows. */
private fun DrawScope.drawClassic(p: Inks) {
    drawRect(p.paper)
    val x = size.width / 100f
    val y = size.height / 100f
    // Pastel cards by day; at night a low wash of the colour over the dark paper.
    fun fill(k: Kind) = if (p.dark) k.default.copy(alpha = 0.22f).compositeOver(p.paper) else k.pastel
    fun card(top: Float, bottom: Float, k: Kind) {
        val at = Offset(8 * x, top * y)
        val s = Size(80 * x, (bottom - top) * y)
        val r = CornerRadius(4 * x)
        drawRoundRect(p.text, at + Offset(3 * x, 3 * x), s, r)
        drawRoundRect(fill(k), at, s, r)
        drawRoundRect(p.text, at, s, r, style = Stroke(1.4f * x))
    }
    card(8f, 56f, Kind.Classes)
    drawRoundRect(p.text, Offset(15 * x, 16 * y), Size(28 * x, 8 * y), CornerRadius(2 * x))
    drawRoundRect(p.text.copy(alpha = 0.5f), Offset(15 * x, 29 * y), Size(46 * x, 5 * y), CornerRadius(2 * x))
    drawRoundRect(p.a, Offset(15 * x, 41 * y), Size(56 * x, 7 * y), CornerRadius(3 * x))
    card(68f, 94f, Kind.Exams)
    drawRoundRect(p.text, Offset(15 * x, 76 * y), Size(22 * x, 7 * y), CornerRadius(2 * x))
}

/** Editorial Home in miniature: a headline, a firm rule, two flat tiles with big figures and one red block. */
private fun DrawScope.drawEditorial(p: Inks) {
    drawRect(p.paper)
    val x = size.width / 100f
    val y = size.height / 100f
    drawRect(p.text, Offset(8 * x, 8 * y), Size(62 * x, 10 * y))
    drawRect(p.text, Offset(8 * x, 21 * y), Size(34 * x, 10 * y))
    drawRect(p.text, Offset(8 * x, 37 * y), Size(84 * x, 1.6f * y))
    for (col in 0 until 2) {
        val at = Offset((8 + col * 43) * x, 44 * y)
        val tile = Size(41 * x, 30 * y)
        drawRect(p.surface, at, tile)
        drawRect(p.rule, at, tile, style = Stroke(0.8f * x))
        drawRect(p.text, at + Offset(5 * x, 14 * y), Size(16 * x, 11 * y))
    }
    drawRect(p.a, Offset(8 * x, 80 * y), Size(84 * x, 13 * y))
}
