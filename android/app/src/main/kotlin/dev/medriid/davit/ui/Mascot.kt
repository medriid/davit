package dev.medriid.davit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.LocalInks

/**
 * The profile mascot: a little printed creature (humanoid, not human) you dress up in
 * Account. Body colour is its own palette; hair is the ink-B plate and the shirt the
 * ink-A plate a touch out of register, so it recolours with the ink pair like everything
 * else. Stored as "type,hair,colour,extra" (see [encode]).
 */
data class Mascot(
    val female: Boolean = false,
    val hair: Int = 0,
    val colour: Int = 0,
    val extra: Int = 0,
) {
    fun encode() = "${if (female) 1 else 0},$hair,$colour,$extra"

    val hairNames: List<String> get() = if (female) FemaleHair else MaleHair
    val extraNames: List<String> get() = listOf("None", "Glasses", "Shades", "Headphones", if (female) "Bow" else "Cap")

    companion object {
        val MaleHair = listOf("Tuft", "Spiky", "Quiff", "Curly", "Mohawk", "Buzz")
        val FemaleHair = listOf("Long", "Bob", "Ponytail", "Buns", "Pigtails", "Pixie")
        val Colours = listOf(
            "Paper" to Color(0xFFF6F2E9),
            "Peach" to Color(0xFFF8CBA8),
            "Butter" to Color(0xFFF7DF8E),
            "Mint" to Color(0xFFBFE4CF),
            "Sky" to Color(0xFFBBD6EF),
            "Lilac" to Color(0xFFDCC8EE),
        )

        fun parse(code: String): Mascot {
            val p = code.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return Mascot()
            return Mascot(p[0] == 1, p[1].coerceIn(0, 5), p[2].coerceIn(0, Colours.lastIndex), p[3].coerceIn(0, 4))
        }
    }
}

/** The mascot in a paper circle with an ink ring. */
@Composable
fun MascotAvatar(mascot: Mascot, size: Dp, modifier: Modifier = Modifier, ring: Dp = 2.dp) {
    val inks = LocalInks.current
    Canvas(
        modifier
            .requiredSize(size)
            .clip(CircleShape)
            .border(ring, inks.text, CircleShape),
    ) { drawMascot(mascot, inks) }
}

/** Shapes in a 100 × 100 grid, scaled to the canvas. */
private class Grid(val u: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(l * u, t * u, r * u, b * u)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    fun Path.mv(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.ln(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.cu(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x * u, y * u)
    fun Path.oval(l: Float, t: Float, r: Float, b: Float) = addOval(rect(l, t, r, b))

    /** The hairline every male style and the pixie share: a cap that dips at the brow. */
    fun Path.cap(top: Float = 16f) {
        mv(22f, 40f); cu(20f, top, 80f, top, 78f, 40f)
        cu(70f, 32f, 58f, 30f, 50f, 33f); cu(42f, 30f, 30f, 32f, 22f, 40f); close()
    }
}

private fun DrawScope.drawMascot(m: Mascot, inks: Inks) = with(Grid(size.minDimension / 100f)) {
    val body = Mascot.Colours[m.colour].second
    val line = Stroke(width = 2.2f * u, cap = StrokeCap.Round)
    val thin = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
    val misreg = Offset(2.2f * u, 1.6f * u)

    /**
     * A solid shape: opaque ink so nothing behind shows through (multiply made hair look
     * see-through), with its outline in register. [offset] slips the ink a touch out of
     * register for the riso feel, on shapes where a ghost edge reads as print, not error.
     */
    fun solid(shape: Path, ink: Color, offset: Boolean = false, outline: Stroke? = thin) {
        if (offset) translate(misreg.x, misreg.y) { drawPath(shape, ink) } else drawPath(shape, ink)
        outline?.let { drawPath(shape, inks.text, style = it) }
    }

    drawRect(inks.paper)
    drawCircle(inks.b.copy(alpha = 0.14f), 36 * u, p(50f, 44f), blendMode = BlendMode.Multiply)

    // ---- behind the head: back hair, buns, pigtails, ponytail -------------------
    if (m.female) {
        val back = when (m.hair) {
            0 -> path { mv(20f, 44f); cu(18f, 10f, 82f, 10f, 80f, 44f); ln(83f, 82f); cu(70f, 88f, 30f, 88f, 17f, 82f); close() }
            1 -> path { mv(19f, 44f); cu(17f, 10f, 83f, 10f, 81f, 44f); ln(83f, 68f); cu(72f, 74f, 28f, 74f, 17f, 68f); close() }
            2 -> path { oval(70f, 18f, 94f, 62f) }
            3 -> path { oval(16f, 6f, 40f, 30f); oval(60f, 6f, 84f, 30f) }
            4 -> path { oval(6f, 42f, 24f, 80f); oval(76f, 42f, 94f, 80f) }
            else -> null
        }
        back?.let { solid(it, inks.b) }
        when (m.hair) {
            2 -> drawCircle(inks.a, 3.2f * u, p(75f, 27f))
            4 -> { drawCircle(inks.a, 3f * u, p(20f, 46f)); drawCircle(inks.a, 3f * u, p(80f, 46f)) }
        }
    }

    // ---- body ----------------------------------------------------------------------
    solid(path { oval(16f, 74f, 84f, 128f) }, inks.a, offset = true, outline = line)
    // Collar.
    drawPath(path { mv(42f, 75f); ln(50f, 82f); ln(58f, 75f) }, inks.text, style = thin)

    // ---- ears (round fins) and head --------------------------------------------------
    for (x in listOf(20f, 80f)) {
        drawCircle(body, 7f * u, p(x, 47f))
        drawCircle(inks.text, 7f * u, p(x, 47f), style = line)
        drawCircle(inks.a.copy(alpha = 0.35f), 3.2f * u, p(x, 47f), blendMode = BlendMode.Multiply)
    }
    val head = rect(20f, 20f, 80f, 74f)
    drawRoundRect(body, head.topLeft, head.size, CornerRadius(27f * u))
    drawRoundRect(inks.text, head.topLeft, head.size, CornerRadius(27f * u), style = line)

    // ---- front hair ------------------------------------------------------------------
    val front: Path? = if (!m.female) when (m.hair) {
        0 -> path { cap(); mv(47f, 21f); cu(44f, 9f, 59f, 6f, 61f, 15f); cu(56f, 12f, 52f, 15f, 53f, 21f); close() }
        1 -> path {
            mv(22f, 40f); ln(21f, 28f); ln(29f, 27f); ln(29f, 15f); ln(38f, 22f); ln(44f, 9f); ln(51f, 20f)
            ln(59f, 10f); ln(63f, 22f); ln(72f, 16f); ln(72f, 27f); ln(79f, 29f); ln(78f, 40f)
            cu(70f, 32f, 58f, 30f, 50f, 33f); cu(42f, 30f, 30f, 32f, 22f, 40f); close()
        }
        2 -> path {
            mv(22f, 40f); cu(19f, 22f, 33f, 13f, 50f, 15f); cu(62f, 4f, 85f, 8f, 82f, 24f)
            cu(78f, 20f, 72f, 20f, 67f, 22f); cu(75f, 28f, 79f, 34f, 78f, 40f)
            cu(70f, 32f, 58f, 30f, 50f, 33f); cu(42f, 30f, 30f, 32f, 22f, 40f); close()
        }
        3 -> path {
            listOf(25f to 37f, 28f to 27f, 36f to 20f, 46f to 16f, 56f to 16f, 65f to 20f, 72f to 27f, 75f to 37f)
                .forEach { (x, y) -> oval(x - 8f, y - 8f, x + 8f, y + 8f) }
            oval(30f, 17f, 70f, 36f)
        }
        4 -> path { mv(43f, 30f); ln(40f, 14f); ln(46f, 16f); ln(47f, 5f); ln(53f, 13f); ln(57f, 6f); ln(58f, 18f); ln(61f, 17f); ln(58f, 31f); close() }
        else -> null
    } else when (m.hair) {
        1, 3 -> path { mv(23f, 39f); cu(21f, 14f, 79f, 14f, 77f, 39f); close() }
        4 -> path { mv(22f, 42f); cu(20f, 15f, 80f, 15f, 78f, 42f); cu(70f, 30f, 56f, 26f, 50f, 31f); cu(44f, 26f, 30f, 30f, 22f, 42f); close() }
        5 -> path { mv(22f, 40f); cu(20f, 13f, 80f, 13f, 78f, 36f); cu(70f, 30f, 56f, 30f, 46f, 36f); cu(38f, 32f, 28f, 34f, 22f, 40f); close() }
        else -> path { mv(22f, 45f); cu(20f, 15f, 80f, 15f, 78f, 45f); cu(74f, 32f, 62f, 26f, 50f, 27f); cu(40f, 28f, 30f, 34f, 22f, 45f); close() }
    }
    // A cap covers the hair; buzz and mohawk get a shaved cap in a light tint of the ink.
    val capped = !m.female && m.extra == 4
    if (!capped) {
        if (!m.female && m.hair >= 4) solid(path { cap(18f) }, lerp(body, inks.b, 0.45f), outline = null)
        front?.let { solid(it, inks.b) }
    }

    // ---- face ------------------------------------------------------------------------
    val shades = m.extra == 2
    if (!shades) {
        for (x in listOf(39f, 61f)) {
            drawOval(inks.text, p(x - 3.8f, 41.5f), Size(7.6f * u, 9.5f * u))
            drawCircle(inks.paper, 1.5f * u, p(x + 1.3f, 44f))
        }
        if (m.female) {
            drawLine(inks.text, p(35.6f, 43f), p(32.4f, 40.6f), thin.width, StrokeCap.Round)
            drawLine(inks.text, p(64.4f, 43f), p(67.6f, 40.6f), thin.width, StrokeCap.Round)
        }
    }
    for (x in listOf(30f, 70f)) drawOval(inks.a.copy(alpha = 0.5f), p(x - 4.5f, 54f), Size(9f * u, 5f * u), blendMode = BlendMode.Multiply)
    val mouth = rect(45f, 52f, 55f, 61f)
    drawArc(inks.text, 20f, 140f, false, mouth.topLeft, mouth.size, style = line)

    // ---- extras ----------------------------------------------------------------------
    when (m.extra) {
        1 -> {
            for (x in listOf(39f, 61f)) drawCircle(inks.text, 8f * u, p(x, 46.5f), style = thin)
            drawLine(inks.text, p(47f, 46f), p(53f, 46f), thin.width)
        }
        2 -> {
            val lenses = path {
                mv(29f, 41f); ln(48f, 41f); cu(48f, 54f, 30f, 54f, 29f, 41f); close()
                mv(52f, 41f); ln(71f, 41f); cu(70f, 54f, 52f, 54f, 52f, 41f); close()
            }
            drawPath(lenses, inks.text)
            drawLine(inks.text, p(26f, 41f), p(74f, 41f), 2.4f * u)
            drawLine(inks.paper, p(33f, 44f), p(37f, 44f), 1.4f * u, StrokeCap.Round)
            drawLine(inks.paper, p(56f, 44f), p(60f, 44f), 1.4f * u, StrokeCap.Round)
        }
        3 -> {
            val band = rect(15f, 10f, 85f, 84f)
            drawArc(inks.text, 180f, 180f, false, band.topLeft, band.size, style = Stroke(3.4f * u, cap = StrokeCap.Round))
            for (x in listOf(13f, 77f)) {
                val cup = rect(x, 37f, x + 10f, 57f)
                drawRoundRect(inks.a, cup.topLeft, cup.size, CornerRadius(4f * u))
                drawRoundRect(inks.text, cup.topLeft, cup.size, CornerRadius(4f * u), style = line)
            }
        }
        4 -> if (m.female) {
            val bow = path {
                mv(66f, 23f); ln(57f, 15f); ln(58f, 31f); close()
                mv(66f, 23f); ln(75f, 15f); ln(74f, 31f); close()
            }
            solid(bow, inks.a)
            drawCircle(inks.text, 2.3f * u, p(66f, 23f))
        } else {
            val cap = path { mv(22f, 34f); cu(22f, 6f, 78f, 6f, 78f, 34f); close() }
            solid(cap, inks.a, outline = line)
            drawLine(inks.text, p(20f, 34f), p(92f, 34f), 3.4f * u, StrokeCap.Round)
            drawCircle(inks.text, 2f * u, p(50f, 12f))
        }
    }
}

/** Account-sheet editor, top to bottom: preview and type, colour, hair, extra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MascotEditor(mascot: Mascot, onChange: (Mascot) -> Unit) {
    val inks = LocalInks.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        MascotAvatar(mascot, 112.dp, ring = 2.5.dp)
        Spacer(Modifier.width(18.dp))
        Column {
            InkText("Type", label = true)
            Spacer(Modifier.height(6.dp))
            ChoiceRow(listOf("Male", "Female"), if (mascot.female) 1 else 0) {
                // Hair styles differ per type, so switching keeps the slot, not the look.
                onChange(mascot.copy(female = it == 1))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    InkText("Colour", label = true)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Mascot.Colours.forEachIndexed { i, (name, c) ->
            val on = i == mascot.colour
            Canvas(
                Modifier
                    .size(40.dp)
                    .border(if (on) 2.5.dp else 1.dp, if (on) inks.text else inks.rule, CircleShape)
                    .clickable(role = Role.RadioButton) { onChange(mascot.copy(colour = i)) }
                    .semantics {
                        contentDescription = name
                        selected = on
                    }
                    .padding(5.dp),
            ) {
                drawCircle(c)
                drawCircle(inks.text, size.minDimension / 2, style = Stroke(1.dp.toPx()))
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    InkText("Hair", label = true)
    Spacer(Modifier.height(4.dp))
    ChoiceRow(mascot.hairNames, mascot.hair) { onChange(mascot.copy(hair = it)) }
    Spacer(Modifier.height(10.dp))
    InkText("Extra", label = true)
    Spacer(Modifier.height(4.dp))
    ChoiceRow(mascot.extraNames, mascot.extra) { onChange(mascot.copy(extra = it)) }
}

/** Chips where the chosen one is solid; wraps onto more lines when it runs out of room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, text ->
            Box(
                Modifier
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { this.selected = i == selected }
                    .padding(vertical = 5.dp),
            ) { InkChip(text, solid = i == selected) }
        }
    }
}
