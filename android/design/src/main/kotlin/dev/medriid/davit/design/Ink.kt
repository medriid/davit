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
enum class Kind(val title: String, val note: String, val default: Color, val pastel: Color) {
    Classes("Classes", "Now and next, the week, your teachers", Color(0xFF0078BF), Color(0xFFCDEFC4)),
    Attendance("Attendance", "Percentages, at-risk courses, VTOP remarks", Color(0xFF00A95C), Color(0xFFC9E4FB)),
    Assignments("Assignments", "What's due, uploads, course materials", Color(0xFFFFB511), Color(0xFFF9DBA9)),
    Exams("Exams and marks", "Exam dates, marks, grades and GPA", Color(0xFFFF48B0), Color(0xFFD5CEF7)),
    Reminders("Reminders", "Your own reminders, registration", Color(0xFFFF6C2F), Color(0xFFFFE590)),
    Events("Events", "Club events and certificates", Color(0xFF765BA7), Color(0xFFF7C9DF)),
    People("Messages and people", "Proctor, messages, faculty, Spot-light", Color(0xFF00838A), Color(0xFFBFE8E3)),
    Vtop("VTOP pages", "Hostel, requests and records", Color(0xFFF65058), Color(0xFFF8C7C2)),
}

/**
 * How hard the inks print: [saturation] and [brightness] scale a spot ink down (1 = as it
 * comes), and [wash] is how much of a card's colour tints its paper. Night print tones the
 * bright spot inks down, so they glow less on dark stock.
 */
@Immutable
data class Tone(val saturation: Float = 1f, val brightness: Float = 1f, val wash: Float = 0.12f)

/** [this] ink printed at [tone]: less saturated and no brighter than it allows. */
fun Color.toned(tone: Tone): Color {
    if (tone.saturation >= 1f && tone.brightness >= 1f) return this
    val (h, s, v) = toHsv().let { Triple(it[0], it[1], it[2]) }
    return hsvColor(h, s * tone.saturation, minOf(v, tone.brightness))
}

/**
 * Two inks on paper, mirroring `design/tokens.css`.
 * Ink A is the bright fill (display type, big shapes, button plate, seal);
 * ink B is the dark ink (headings, lines, halftone dots, buttons);
 * [text] is a deeper pull of ink B for small text, since ink B itself is too light.
 * Overlaps are never hand-picked: draw with [blend], multiply on paper; on the dark
 * "night print" paper inks simply sit on top, as screening them made overlaps glow.
 *
 * Cards are printed per [Kind]: [kinds] holds your colour for each (null for the
 * defaults), toned by [tone], and with [tinted] their paper takes a wash of it too.
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
    val kinds: List<Color?>? = null,
    val tinted: Boolean = true,
    /** The same inks on day paper, for things that print the same at night (the mascot). */
    val dayTwin: Inks? = null,
    val tone: Tone = Tone(),
    /**
     * Sticker print, by day: cards in pastel with black outlines and a hard black shadow,
     * buttons in ink A with black type.
     */
    val sticker: Boolean = false,
    /**
     * Monochrome glass: no colour at all, cards of frosted glass (see-through paper with a
     * fine light edge and a soft shadow) over a soft grey sheet.
     */
    val glass: Boolean = false,
) {
    /** Frosted glass: the card's see-through fill, its fine edge, and the soft shadow under it. */
    val glassFill: Color get() = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    val glassEdge: Color get() = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.95f)
    val glassShadow: Color get() = Color.Black.copy(alpha = if (dark) 0.45f else 0.10f)

    /** Dashed table rules and quiet borders. */
    val rule: Color get() = text.copy(alpha = ruleAlpha)

    /** How overlapping ink combines on this paper. */
    val blend: BlendMode get() = if (dark) BlendMode.SrcOver else BlendMode.Multiply

    // Worked out once: each kind's plate and its wash. The defaults print toned; a colour you
    // picked prints as picked by day and toned like the rest at night, lifted so it shows.
    private val plates: List<Color> by lazy {
        Kind.entries.map { k ->
            val yours = kinds?.getOrNull(k.ordinal)
            when {
                dark -> (yours ?: k.default).toned(tone).liftedFor(paper, 2.2f)
                yours != null -> yours
                else -> k.default.toned(tone)
            }
        }
    }
    private val washes: List<Color> by lazy {
        Kind.entries.map { k ->
            val yours = kinds?.getOrNull(k.ordinal)
            when {
                // Sticker cards: the kind's pastel, or a light pull of your own colour.
                sticker -> if (yours != null) lerp(paper, yours, 0.42f) else k.pastel
                else -> lerp(paper, plates[k.ordinal], tone.wash)
            }
        }
    }

    /** The plate behind a card of [kind]: a black shadow in sticker print, else its ink (ink A for none). */
    fun plate(kind: Kind?): Color = when {
        glass -> glassShadow
        sticker -> text
        kind == null -> a
        else -> plates[kind.ordinal]
    }

    /** The paper of a card of [kind]: a wash of its colour when [tinted], else plain paper. */
    fun fill(kind: Kind?): Color = if (glass) glassFill else if (kind == null || !tinted) paper else washes[kind.ordinal]

    /** The ink a kind is marked in (icons, a stripe): its plate, which in sticker print is black, so its own colour. Grey in glass. */
    fun mark(kind: Kind): Color = if (glass) b else plates[kind.ordinal]
}

object InkPairs {
    val Paper = Color(0xFFF4F2EC)

    val ScarletTeal = Inks("Scarlet + Teal", Paper, Color(0xFFF65058), Color(0xFF00838A), Color(0xFF00585D))
    val SunflowerFederal = Inks("Sunflower + Federal Blue", Paper, Color(0xFFFFB511), Color(0xFF3D5588), Color(0xFF2C3F6B))
    val PinkBlue = Inks("Fluorescent Pink + Blue", Paper, Color(0xFFFF48B0), Color(0xFF0078BF), Color(0xFF00598F))
    val RedBlack = Inks("Bright Red + Black", Paper, Color(0xFFF15060), Color(0xFF2A2A2A), Color(0xFF1F1F1F), ruleAlpha = 0.3f)
    val LimeInk = Inks("Lime + Ink", Paper, Color(0xFFD3F53B), Color(0xFF2A2A2A), Color(0xFF161616), ruleAlpha = 0.3f)

    private val dayPairs = listOf(ScarletTeal, SunflowerFederal, PinkBlue, RedBlack, LimeInk)

    // Monochrome glass: black, white and greys only; the cards are frosted glass.
    val MonoGlass = Inks("Monochrome glass", Color(0xFFE5E5E5), Color(0xFF141414), Color(0xFF3A3A3A), Color(0xFF111111), ruleAlpha = 0.22f, glass = true)
    private val MonoGlassNight = Inks("Monochrome glass", Color(0xFF0D0D0D), Color(0xFFF2F2F2), Color(0xFFCFCFCF), Color(0xFFEDEDED), ruleAlpha = 0.22f, dark = true, glass = true)

    // By day: sticker print, pastel cards with black outlines and hard black shadows,
    // near-black type, and each pair's ink A on the buttons.
    private val DayText = Color(0xFF161616)
    private val DayTone = Tone(saturation = 0.72f, brightness = 0.92f, wash = 0.08f)

    val all: List<Inks> = dayPairs.map {
        it.copy(paper = Paper, text = DayText, a = it.a.toned(Tone(0.9f, 1f)), tone = DayTone, sticker = true, ruleAlpha = 0.3f)
    } + MonoGlass

    // Night print: warm charcoal stock, every ink toned down so nothing glows, ink B lifted so it reads.
    val NightPaper = Color(0xFF1E1C19)
    private val NightText = Color(0xFFE8E2D6)
    private val NightTone = Tone(saturation = 0.6f, brightness = 0.82f, wash = 0.08f)

    val night: List<Inks> = dayPairs.map { p ->
        val b = (if (p === RedBlack || p === LimeInk) Color(0xFFD9D6CF) else p.b.toned(Tone(0.75f, 1f))).liftedFor(NightPaper, 4f)
        Inks(p.name, NightPaper, p.a.toned(NightTone), b, NightText, ruleAlpha = 0.3f, dark = true, tone = NightTone)
    } + MonoGlassNight

    /** The paper version of [inks]: for things that should print the same at night (the mascot). */
    fun onPaper(inks: Inks): Inks = if (!inks.dark) inks else inks.dayTwin?.takeIf { !it.dark }
        ?: all.firstOrNull { it.name == inks.name }
        ?: inks.copy(paper = Paper, text = textFor(Paper, inks.b), dark = false, dayTwin = null)

    /** Pair [index], on paper or at night. */
    fun of(index: Int, dark: Boolean): Inks = (if (dark) night else all)[index.coerceIn(0, all.lastIndex)]
}

val LocalInks = staticCompositionLocalOf { InkPairs.all.first() }
