package dev.medriid.davit.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * Paper with grain on top (`body::after` in tokens.css): a static noise tile drawn over
 * everything at [alpha], multiplied, so the screen doesn't read as flat vector art.
 * It only draws; touches pass straight through.
 */
@Composable
fun Grain(
    modifier: Modifier = Modifier,
    alpha: Float = 0.2f,
    content: @Composable BoxScope.() -> Unit,
) {
    val inks = LocalInks.current
    val grain = remember { grainBrush() }
    if (inks.glass) {
        // Glass: no grain, a soft grey sheet with two pale glows, so the frosted cards have something to show through.
        Box(
            modifier.drawBehind {
                drawRect(inks.paper)
                val glow = if (inks.dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f)
                val shade = if (inks.dark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.07f)
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), Offset(size.width * 0.15f, size.height * 0.12f), size.width * 0.9f))
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), Offset(size.width * 0.95f, size.height * 0.6f), size.width * 0.8f), radius = size.width * 0.8f, center = Offset(size.width * 0.95f, size.height * 0.6f))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, shade)))
            },
            content = content,
        )
        return
    }
    Box(
        modifier
            .background(inks.paper)
            .drawWithContent {
                drawContent()
                drawRect(grain, alpha = alpha, blendMode = inks.blend)
            },
        content = content,
    )
}

/**
 * Black noise with alpha up to 0.55, like the SVG feTurbulence filter. A coarse octave
 * (3 px blobs, smoothed) under a fine one gives it the uneven tooth of real paper.
 * Seeded, so every launch prints the same sheet.
 */
private fun grainBrush(size: Int = 192): ShaderBrush {
    val rnd = Random(7)
    val coarseN = size / 3
    val coarse = FloatArray(coarseN * coarseN) { rnd.nextFloat() }
    fun coarseAt(x: Int, y: Int): Float {
        val fx = x / 3f
        val fy = y / 3f
        val x0 = fx.toInt() % coarseN
        val y0 = fy.toInt() % coarseN
        val x1 = (x0 + 1) % coarseN
        val y1 = (y0 + 1) % coarseN
        val tx = fx - fx.toInt()
        val ty = fy - fy.toInt()
        val top = coarse[y0 * coarseN + x0] * (1 - tx) + coarse[y0 * coarseN + x1] * tx
        val bottom = coarse[y1 * coarseN + x0] * (1 - tx) + coarse[y1 * coarseN + x1] * tx
        return top * (1 - ty) + bottom * ty
    }
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val n = 0.55f * rnd.nextFloat() + 0.45f * coarseAt(x, y)
            val a = (n * 0.55f * 255).toInt().coerceIn(0, 255)
            pixels[y * size + x] = a shl 24
        }
    }
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
