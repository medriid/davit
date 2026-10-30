package dev.medriid.davit.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Small colour sums for choosing your own inks: contrast as WCAG measures it, HSV for the
// wheel, and nudging a colour lighter or darker until it reads on a given paper.

/** WCAG contrast ratio, 1 (same) to 21 (black on white). */
fun Color.contrast(other: Color): Float {
    val a = luminance() + 0.05f
    val b = other.luminance() + 0.05f
    return max(a, b) / min(a, b)
}

/** Hue (0–360), saturation and value (0–1), as the colour wheel works in them. */
fun Color.toHsv(): FloatArray {
    val mx = max(red, max(green, blue))
    val mn = min(red, min(green, blue))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == red -> 60f * (((green - blue) / d) % 6f)
        mx == green -> 60f * ((blue - red) / d + 2f)
        else -> 60f * ((red - green) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
}

fun hsvColor(h: Float, s: Float, v: Float): Color = Color.hsv(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

/** "#F65058". */
fun Color.hex(): String {
    val argb = (red * 255 + 0.5f).toInt() shl 16 or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()
    return "#" + String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)
}

/** "#f65058", "F65058" or "f50" → the colour; null if it isn't one. */
fun parseHex(text: String): Color? {
    var s = text.trim().removePrefix("#")
    if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
    if (s.length != 6 || s.any { it.digitToIntOrNull(16) == null }) return null
    return Color(0xFF000000 or s.toLong(16))
}

/** The colour as a stored number (opaque ARGB). */
fun Color.argb(): Long = 0xFF000000 or hex().removePrefix("#").toLong(16)

/**
 * This colour moved lighter or darker (whichever [paper] calls for) in small steps until it
 * reaches [min] contrast with it; kept as it is when it already does.
 */
fun Color.liftedFor(paper: Color, min: Float): Color {
    if (contrast(paper) >= min) return this
    // Towards whichever of black or white the paper sets off better (on mid grey, black).
    val towards = if (Color.Black.contrast(paper) >= Color.White.contrast(paper)) Color.Black else Color.White
    for (i in 1..20) {
        val c = lerp(this, towards, i / 20f)
        if (c.contrast(paper) >= min) return c
    }
    return towards
}

/**
 * Text for [paper] in the hue of [ink]: a deep pull of it on light paper, a pale one on
 * dark, at least 7:1 either way (WCAG AAA for body text), or as close as the paper allows.
 */
fun textFor(paper: Color, ink: Color): Color {
    val dark = Color.White.contrast(paper) > Color.Black.contrast(paper)
    val start = if (dark) lerp(ink, Color.White, 0.82f) else lerp(ink, Color.Black, 0.35f)
    return start.liftedFor(paper, 7f)
}

/** A real Riso spot ink, for the swatches under the wheel. */
data class RisoInk(val name: String, val color: Color)

/** Riso's own spot colours (their published screen values), as starting points. */
val RisoInks = listOf(
    RisoInk("Scarlet", Color(0xFFF65058)),
    RisoInk("Bright Red", Color(0xFFF15060)),
    RisoInk("Raspberry", Color(0xFFD1517A)),
    RisoInk("Fluorescent Pink", Color(0xFFFF48B0)),
    RisoInk("Orange", Color(0xFFFF6C2F)),
    RisoInk("Sunflower", Color(0xFFFFB511)),
    RisoInk("Yellow", Color(0xFFFFE800)),
    RisoInk("Light Lime", Color(0xFFE3ED55)),
    RisoInk("Kelly Green", Color(0xFF67B346)),
    RisoInk("Green", Color(0xFF00A95C)),
    RisoInk("Hunter Green", Color(0xFF407060)),
    RisoInk("Teal", Color(0xFF00838A)),
    RisoInk("Mint", Color(0xFF82D8D5)),
    RisoInk("Aqua", Color(0xFF5EC8E5)),
    RisoInk("Cornflower", Color(0xFF62A8E5)),
    RisoInk("Blue", Color(0xFF0078BF)),
    RisoInk("Medium Blue", Color(0xFF3255A4)),
    RisoInk("Federal Blue", Color(0xFF3D5588)),
    RisoInk("Purple", Color(0xFF765BA7)),
    RisoInk("Grape", Color(0xFF6C5D80)),
    RisoInk("Burgundy", Color(0xFF914E72)),
    RisoInk("Brick", Color(0xFFA75154)),
    RisoInk("Flat Gold", Color(0xFFBB8B41)),
    RisoInk("Black", Color(0xFF2A2A2A)),
)
