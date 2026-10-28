package dev.medriid.davit.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/**
 * Two inks on paper, mirroring `design/tokens.css`.
 * Ink A is the bright fill (display type, big shapes, button plate, seal);
 * ink B is the dark ink (headings, lines, halftone dots, buttons);
 * [text] is a deeper pull of ink B for small text, since ink B itself is too light.
 * Overlaps are never hand-picked: draw with [blend], multiply on paper and screen on the
 * dark "night print" paper, where multiply would swallow the ink.
 */
@Immutable
data class Inks(
    val name: String,
    val paper: Color,
    val a: Color,
    val b: Color,
    val text: Color,
    val ruleAlpha: Float = 0.35f,
    val dark: Boolean = false,
) {
    /** Dashed table rules and quiet borders. */
    val rule: Color get() = text.copy(alpha = ruleAlpha)

    /** How overlapping ink combines on this paper. */
    val blend: BlendMode get() = if (dark) BlendMode.Screen else BlendMode.Multiply
}

object InkPairs {
    private val Paper = Color(0xFFF1F0EB)

    val ScarletTeal = Inks("Scarlet + Teal", Paper, Color(0xFFF65058), Color(0xFF00838A), Color(0xFF00585D))
    val SunflowerFederal = Inks("Sunflower + Federal Blue", Paper, Color(0xFFFFB511), Color(0xFF3D5588), Color(0xFF2C3F6B))
    val PinkBlue = Inks("Fluorescent Pink + Blue", Paper, Color(0xFFFF48B0), Color(0xFF0078BF), Color(0xFF00598F))
    val RedBlack = Inks("Bright Red + Black", Paper, Color(0xFFF15060), Color(0xFF2A2A2A), Color(0xFF1F1F1F), ruleAlpha = 0.3f)

    val all = listOf(ScarletTeal, SunflowerFederal, PinkBlue, RedBlack)

    // Night print: the same pairs pulled on dark stock, with ink B lifted so it reads.
    private val NightPaper = Color(0xFF17191C)
    private val NightText = Color(0xFFE9E6DD)

    val night = listOf(
        Inks("Scarlet + Teal", NightPaper, Color(0xFFF65058), Color(0xFF2BB5BC), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Sunflower + Federal Blue", NightPaper, Color(0xFFFFB511), Color(0xFF8198D2), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Fluorescent Pink + Blue", NightPaper, Color(0xFFFF48B0), Color(0xFF4AA8F0), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Bright Red + Black", NightPaper, Color(0xFFF15060), Color(0xFFD9D6CF), Color(0xFFEDEAE3), ruleAlpha = 0.3f, dark = true),
    )

    /** Pair [index], on paper or at night. */
    fun of(index: Int, dark: Boolean): Inks = (if (dark) night else all)[index.coerceIn(0, all.lastIndex)]
}

val LocalInks = staticCompositionLocalOf { InkPairs.ScarletTeal }
