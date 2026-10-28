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

    fun display(size: TextUnit) = TextStyle(
        fontFamily = RisoFonts.Display,
        fontSize = size,
        lineHeight = 0.9.em,
        fontFeatureSettings = Tabular,
    )

    val body = TextStyle(
        fontFamily = RisoFonts.Body,
        fontSize = 16.sp,
        lineHeight = 1.45.em,
        fontFeatureSettings = Tabular,
    )

    val small = body.copy(fontSize = 14.sp, lineHeight = 1.4.em)

    /** Card titles: course names, assignment names. */
    val title = body.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.25.em)

    /** Uppercase labels and chips. Callers uppercase the string (InkText(label = true) does). */
    val label = TextStyle(
        fontFamily = RisoFonts.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.1.em,
        fontFeatureSettings = Tabular,
    )

    val button = label.copy(fontSize = 15.sp, letterSpacing = 0.06.em)
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
