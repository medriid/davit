package dev.medriid.davit.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a photo into a page image: upright (EXIF), cut to its four corners and
 * straightened, turned, filtered. All on Android's Bitmap and Matrix, so the warp runs in
 * native code; only the filters go through the pixel arrays in [Scan].
 */
object Render {

    /** The photo, upright, no wider or taller than [maxSide]. */
    fun load(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turned = upright(raw, orientation(file))
        return fit(turned, maxSide)
    }

    private fun orientation(file: File): Int =
        runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun upright(b: Bitmap, exif: Int): Bitmap {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true).also { if (it != b) b.recycle() }
    }

    fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val side = max(b.width, b.height)
        if (side <= maxSide) return b
        val k = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
            .also { if (it != b) b.recycle() }
    }

    /** The part of [b] inside [q], straightened to a rectangle the size of its longer edges. */
    fun warp(b: Bitmap, q: Quad): Bitmap {
        if (q == Quad.Full) return b
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val src = floatArrayOf(q.tl.x * w, q.tl.y * h, q.tr.x * w, q.tr.y * h, q.br.x * w, q.br.y * h, q.bl.x * w, q.bl.y * h)
        val outW = max(hypot(src[2] - src[0], src[3] - src[1]), hypot(src[4] - src[6], src[5] - src[7])).roundToInt().coerceAtLeast(8)
        val outH = max(hypot(src[6] - src[0], src[7] - src[1]), hypot(src[4] - src[2], src[5] - src[3])).roundToInt().coerceAtLeast(8)
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return b
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    fun turn(b: Bitmap, quarters: Int): Bitmap {
        val q = ((quarters % 4) + 4) % 4
        if (q == 0) return b
        val m = Matrix().apply { postRotate(90f * q) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun filter(b: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.Original) return b
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val out = Scan.apply(f, px, b.width, b.height)
        return Bitmap.createBitmap(out, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /** One shot, finished: upright, cut, turned, filtered, at most [maxSide]. */
    fun shot(file: File, quad: Quad?, turns: Int, filter: ScanFilter, maxSide: Int): Bitmap? {
        // Read a little larger than needed, so the cut-out keeps its sharpness.
        val photo = load(file, (maxSide * 1.35f).roundToInt()) ?: return null
        val cut = fit(warp(photo, quad ?: Quad.Full), maxSide)
        return filter(turn(cut, turns), filter)
    }

    /** A small copy for page finding. */
    fun detect(file: File): Quad? {
        val b = load(file, 360) ?: return null
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Scan.findPage(px, b.width, b.height)
    }

    /** A finished page image back from its JPEG, no wider or taller than [maxSide]. */
    fun decode(jpeg: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return fit(b, maxSide)
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
