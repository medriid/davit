package dev.medriid.davit.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * What a card is about, so each kind prints in its own ink and you can tell them apart at
 * a glance: a class from an assignment from an exam. The colours are yours to change.
 */
enum class Kind(val title: String, val note: String, val default: Color) {
    Classes("Classes", "Now and next, the week, your teachers", Color(0xFF0078BF)),
    Attendance("Attendance", "Percentages, at-risk courses, VTOP remarks", Color(0xFF00A95C)),
    Assignments("Assignments", "What's due, uploads, course materials", Color(0xFFFFB511)),
    Exams("Exams and marks", "Exam dates, marks, grades and GPA", Color(0xFFFF48B0)),
    Reminders("Reminders", "Your own reminders, registration", Color(0xFFFF6C2F)),
    Events("Events", "Club events and certificates", Color(0xFF765BA7)),
    People("Messages and people", "Proctor, messages, faculty, Spot-light", Color(0xFF00838A)),
    Vtop("VTOP pages", "Hostel, requests and records", Color(0xFFF65058)),
}

/**
 * Two inks on paper, mirroring `design/tokens.css`.
 * Ink A is the bright fill (display type, big shapes, button plate, seal);
 * ink B is the dark ink (headings, lines, halftone dots, buttons);
 * [text] is a deeper pull of ink B for small text, since ink B itself is too light.
 * Overlaps are never hand-picked: draw with [blend], multiply on paper and screen on the
 * dark "night print" paper, where multiply would swallow the ink.
 *
 * Cards are printed per [Kind]: [kinds] holds your colour for each (null for the
 * defaults), and with [tinted] their paper takes a wash of it too.
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
    val kinds: List<Color>? = null,
    val tinted: Boolean = true,
    /** The same inks on day paper, for things that print the same at night (the mascot). */
    val dayTwin: Inks? = null,
) {
    /** Dashed table rules and quiet borders. */
    val rule: Color get() = text.copy(alpha = ruleAlpha)

    /** How overlapping ink combines on this paper. */
    val blend: BlendMode get() = if (dark) BlendMode.Screen else BlendMode.Multiply

    // Worked out once: each kind's plate (lifted at night so it shows on dark stock) and its wash.
    private val plates: List<Color> by lazy {
        Kind.entries.map { k -> (kinds?.getOrNull(k.ordinal) ?: k.default).let { if (dark) it.liftedFor(paper, 2.2f) else it } }
    }
    private val washes: List<Color> by lazy { plates.map { lerp(paper, it, if (dark) 0.16f else 0.12f) } }

    /** The plate behind a card of [kind]; ink A for none. */
    fun plate(kind: Kind?): Color = if (kind == null) a else plates[kind.ordinal]

    /** The paper of a card of [kind]: a wash of its colour when [tinted], else plain paper. */
    fun fill(kind: Kind?): Color = if (kind == null || !tinted) paper else washes[kind.ordinal]
}

object InkPairs {
    val Paper = Color(0xFFF1F0EB)

    val ScarletTeal = Inks("Scarlet + Teal", Paper, Color(0xFFF65058), Color(0xFF00838A), Color(0xFF00585D))
    val SunflowerFederal = Inks("Sunflower + Federal Blue", Paper, Color(0xFFFFB511), Color(0xFF3D5588), Color(0xFF2C3F6B))
    val PinkBlue = Inks("Fluorescent Pink + Blue", Paper, Color(0xFFFF48B0), Color(0xFF0078BF), Color(0xFF00598F))
    val RedBlack = Inks("Bright Red + Black", Paper, Color(0xFFF15060), Color(0xFF2A2A2A), Color(0xFF1F1F1F), ruleAlpha = 0.3f)

    val all = listOf(ScarletTeal, SunflowerFederal, PinkBlue, RedBlack)

    // Night print: the same pairs pulled on dark stock, with ink B lifted so it reads.
    val NightPaper = Color(0xFF17191C)
    private val NightText = Color(0xFFE9E6DD)

    val night = listOf(
        Inks("Scarlet + Teal", NightPaper, Color(0xFFF65058), Color(0xFF2BB5BC), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Sunflower + Federal Blue", NightPaper, Color(0xFFFFB511), Color(0xFF8198D2), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Fluorescent Pink + Blue", NightPaper, Color(0xFFFF48B0), Color(0xFF4AA8F0), NightText, ruleAlpha = 0.3f, dark = true),
        Inks("Bright Red + Black", NightPaper, Color(0xFFF15060), Color(0xFFD9D6CF), Color(0xFFEDEAE3), ruleAlpha = 0.3f, dark = true),
    )

    /** The paper version of [inks]: for things that should print the same at night (the mascot). */
    fun onPaper(inks: Inks): Inks = if (!inks.dark) inks else inks.dayTwin?.takeIf { !it.dark }
        ?: all.firstOrNull { it.name == inks.name }
        ?: inks.copy(paper = Paper, text = textFor(Paper, inks.b), dark = false, dayTwin = null)

    /** Pair [index], on paper or at night. */
    fun of(index: Int, dark: Boolean): Inks = (if (dark) night else all)[index.coerceIn(0, all.lastIndex)]
}

val LocalInks = staticCompositionLocalOf { InkPairs.ScarletTeal }
