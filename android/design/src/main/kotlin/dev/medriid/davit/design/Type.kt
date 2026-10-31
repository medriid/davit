package dev.medriid.davit.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Bundled fonts (all OFL). See `design/FONTS.md`. */
object RisoFonts {
    /** Headings and big numbers only. */
    val Display = FontFamily(Font(R.font.bagel_fat_one, FontWeight.Normal))

    /** Body and UI. */
    val Body = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
    )

    /** Bagel's cap height as a fraction of the em (726/1000 units). */
    const val DisplayCapRatio = 0.734f
}

/**
 * Sizes are set for reading on a phone at arm's length: body 16 sp, nothing under 12 sp.
 * Display type is for numbers and a few headings; titles inside cards use [title].
 */
object RisoType {
    private const val Tabular = "tnum"

    /**
     * Soft look: the phone's own sans-serif instead of Plex Mono, numbers in a light weight.
     * Set by [RisoTheme] from the inks, before anything below it composes.
     */
    var soft = false

    /** Editorial look: a heavy grotesk set tight for headlines and numbers, plain sans for reading. */
    var editorial = false

    private val sans = FontFamily.SansSerif
    private val plain get() = soft || editorial

    fun display(size: TextUnit) = when {
        editorial -> TextStyle(fontFamily = sans, fontWeight = FontWeight.Black, fontSize = size, lineHeight = 0.96.em, letterSpacing = (-0.04).em, fontFeatureSettings = Tabular)
        soft -> TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = size, lineHeight = 1.05.em, letterSpacing = (-0.02).em, fontFeatureSettings = Tabular)
        else -> TextStyle(
            fontFamily = RisoFonts.Display,
            fontSize = size,
            lineHeight = 0.9.em,
            fontFeatureSettings = Tabular,
        )
    }

    val body: TextStyle
        get() = if (plain) TextStyle(fontFamily = sans, fontSize = 16.sp, lineHeight = 1.4.em, fontFeatureSettings = Tabular)
        else TextStyle(
            fontFamily = RisoFonts.Body,
            fontSize = 16.sp,
            lineHeight = 1.45.em,
            fontFeatureSettings = Tabular,
        )

    val small: TextStyle get() = body.copy(fontSize = 14.sp, lineHeight = 1.4.em)

    /** Card titles: course names, assignment names. */
    val title: TextStyle
        get() = body.copy(
            fontSize = 19.sp,
            fontWeight = when {
                editorial -> FontWeight.Bold
                soft -> FontWeight.Medium
                else -> FontWeight.SemiBold
            },
            lineHeight = 1.25.em,
            letterSpacing = if (editorial) (-0.01).em else 0.em,
        )

    /** Uppercase labels and chips. Callers uppercase the string (InkText(label = true) does). */
    val label: TextStyle
        get() = TextStyle(
            fontFamily = if (plain) sans else RisoFonts.Body,
            fontWeight = when {
                editorial -> FontWeight.Bold
                soft -> FontWeight.Medium
                else -> FontWeight.SemiBold
            },
            fontSize = if (editorial) 11.sp else 12.sp,
            letterSpacing = when {
                editorial -> 0.14.em
                soft -> 0.08.em
                else -> 0.1.em
            },
            fontFeatureSettings = Tabular,
        )

    val button: TextStyle get() = label.copy(fontSize = if (editorial) 14.sp else 15.sp, letterSpacing = if (soft) 0.02.em else 0.06.em)
}

/**
 * Crops display text to its ink: from cap height above the first baseline down to the
 * last baseline. Bagel reserves a tall ascent for Hangul, which otherwise leaves an empty
 * band above every number. Descenders still draw; they just don't take layout space.
 */
fun Modifier.trimToCaps(fontSize: TextUnit, capRatio: Float = RisoFonts.DisplayCapRatio): Modifier =
    layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
        val first = p[FirstBaseline]
        val last = p[LastBaseline]
        if (first == AlignmentLine.Unspecified) {
            return@layout layout(p.width, p.height) { p.place(0, 0) }
        }
        val top = first - (fontSize.toPx() * capRatio).roundToInt()
        layout(p.width, last - top) { p.place(0, -top) }
    }
