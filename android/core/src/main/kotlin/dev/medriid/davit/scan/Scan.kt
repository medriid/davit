package dev.medriid.davit.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point in an image, as fractions of its width and height (0..1), so it fits any resolution. */
data class Pt(val x: Float, val y: Float)

/** A page's four corners, clockwise from top left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    /** Area as a fraction of the image (shoelace). */
    val area: Float
        get() {
            val c = corners
            var s = 0f
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                s += a.x * b.y - b.x * a.y
            }
            return abs(s) / 2
        }

    /** True when every turn goes the same way: a proper page, not a bow tie. */
    val convex: Boolean
        get() {
            val c = corners
            var sign = 0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % 4]
                val d = c[(i + 2) % 4]
                val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s != 0) {
                    if (sign != 0 && s != sign) return false
                    sign = s
                }
            }
            return true
        }

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    /** This quad on the image turned [quarters] × 90° clockwise. */
    fun rotated(quarters: Int): Quad {
        var q = this
        repeat(((quarters % 4) + 4) % 4) {
            fun r(p: Pt) = Pt(1f - p.y, p.x)
            // After turning, the old bottom-left corner is the new top-left.
            q = Quad(r(q.bl), r(q.tl), r(q.tr), r(q.br))
        }
        return q
    }

    companion object {
        val Full = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        /** A little inside the edges: a starting point to drag from when nothing was found. */
        val Inset = Quad(Pt(0.06f, 0.06f), Pt(0.94f, 0.06f), Pt(0.94f, 0.94f), Pt(0.06f, 0.94f))
    }
}

/** How a page is cleaned up. */
enum class ScanFilter(val label: String) {
    Original("Original"),
    /** Shadows and the paper's tint lifted, colours kept: the everyday choice for notes and sheets. */
    Enhanced("Enhanced"),
    Gray("Grey"),
    /** Crisp black on white, like a photocopy: smallest files, best for text. */
    Mono("Black & white"),
}

/**
 * The scanner's image work, on plain ARGB pixel arrays so it runs (and is tested) on the
 * laptop: finding the page in a photo, and the filters.
 */
object Scan {

    /**
     * The sheet of paper in a photo, or null if there isn't a clear one. Works on a small
     * copy (a few hundred pixels across is plenty): paper is the large bright region, so
     * threshold by Otsu, keep the biggest connected blob, and take its extreme corners.
     */
    fun findPage(px: IntArray, w: Int, h: Int): Quad? {
        if (w < 16 || h < 16 || px.size < w * h) return null
        val lum = IntArray(w * h) { luminance(px[it]) }
        val smooth = boxBlur(lum, w, h, 1)
        val t = otsu(smooth)
        val bright = BooleanArray(w * h) { smooth[it] > t }

        // The biggest 4-connected bright blob.
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        var best = -1
        var bestSize = 0
        var next = 1
        for (start in 0 until w * h) {
            if (!bright[start] || label[start] != 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                if (x > 0) tail = visit(p - 1, next, bright, label, queue, tail)
                if (x < w - 1) tail = visit(p + 1, next, bright, label, queue, tail)
                if (y > 0) tail = visit(p - w, next, bright, label, queue, tail)
                if (y < h - 1) tail = visit(p + w, next, bright, label, queue, tail)
            }
            if (tail > bestSize) {
                bestSize = tail
                best = next
            }
            next++
        }
        if (best < 0) return null
        val share = bestSize.toFloat() / (w * h)
        // A blob that is nearly the whole frame means the page fills it: nothing to cut.
        if (share < 0.12f || share > 0.97f) return null

        var tl = 0
        var tr = 0
        var br = 0
        var bl = 0
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE
        var maxDiff = Int.MIN_VALUE
        for (p in 0 until w * h) {
            if (label[p] != best) continue
            val x = p % w
            val y = p / w
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
            if (d < minDiff) { minDiff = d; bl = p }
        }
        fun pt(p: Int) = Pt((p % w + 0.5f) / w, (p / w + 0.5f) / h)
        val q = Quad(pt(tl), pt(tr), pt(br), pt(bl))
        return q.takeIf { it.convex && it.area > 0.1f }
    }

    private fun visit(q: Int, id: Int, bright: BooleanArray, label: IntArray, queue: IntArray, tail: Int): Int {
        if (!bright[q] || label[q] != 0) return tail
        label[q] = id
        queue[tail] = q
        return tail + 1
    }

    /** Applies [filter] in place-free fashion; [px] is left alone. */
    fun apply(filter: ScanFilter, px: IntArray, w: Int, h: Int): IntArray = when (filter) {
        ScanFilter.Original -> px.copyOf()
        ScanFilter.Enhanced -> enhance(px, w, h)
        ScanFilter.Gray -> gray(px, w, h)
        ScanFilter.Mono -> mono(px, w, h)
    }

    /**
     * Divides out the lighting: each pixel over a heavily blurred copy of the brightness
     * (the paper's own shade, shadows included), so the paper comes out white and the ink
     * keeps its colour. Then a gentle contrast stretch.
     */
    fun enhance(px: IntArray, w: Int, h: Int): IntArray {
        val lum = IntArray(w * h) { luminance(px[it]) }
        val bg = boxBlur(lum, w, h, max(4, max(w, h) / 14))
        val out = IntArray(w * h)
        for (i in out.indices) {
            val c = px[i]
            val b = max(bg[i], 24)
            // 248 rather than 255: a hair under white keeps faint pencil visible.
            val k = 248f / b
            fun ch(v: Int): Int {
                val n = v * k
                // Push mid-tones a little darker so text firms up.
                val s = if (n < 200f) n * 0.92f else n
                return s.roundToInt().coerceIn(0, 255)
            }
            out[i] = (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
        return out
    }

    /** Lighting removed as in [enhance], in grey, with the darkest and lightest 1% clipped. */
    fun gray(px: IntArray, w: Int, h: Int): IntArray {
        val e = enhance(px, w, h)
        val lum = IntArray(w * h) { luminance(e[it]) }
        val (lo, hi) = percentiles(lum, 0.01f, 0.99f)
        val span = max(1, hi - lo)
        return IntArray(w * h) {
            val v = ((lum[it] - lo) * 255 / span).coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /**
     * Black and white by local threshold: a pixel is ink when it's clearly darker than its
     * neighbourhood (Bradley's method on an integral image), so a shadowed corner doesn't
     * go solid black and faint writing survives.
     */
    fun mono(px: IntArray, w: Int, h: Int): IntArray {
        val e = enhance(px, w, h)
        val lum = IntArray(w * h) { luminance(e[it]) }
        val r = max(4, min(w, h) / 24)
        val mean = boxBlur(lum, w, h, r)
        return IntArray(w * h) {
            val ink = lum[it] < mean[it] * 0.86f && lum[it] < 200
            if (ink) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    fun luminance(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    /**
     * Mean over a (2r+1)² window, edges clamped: a running sum across each row, then down
     * each column. O(pixels) whatever r is, and only one extra array, which matters at
     * page resolution on a phone.
     */
    fun boxBlur(v: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0L
            var lo = 0
            var hi = -1
            for (x in 0 until w) {
                val x1 = min(w - 1, x + r)
                val x0 = max(0, x - r)
                while (hi < x1) sum += v[row + ++hi]
                while (lo < x0) sum -= v[row + lo++]
                tmp[row + x] = (sum / (hi - lo + 1)).toInt()
            }
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            var sum = 0L
            var lo = 0
            var hi = -1
            for (y in 0 until h) {
                val y1 = min(h - 1, y + r)
                val y0 = max(0, y - r)
                while (hi < y1) sum += tmp[++hi * w + x]
                while (lo < y0) sum -= tmp[lo++ * w + x]
                out[y * w + x] = (sum / (hi - lo + 1)).toInt()
            }
        }
        return out
    }

    /** Otsu's threshold over 0..255 values. */
    fun otsu(v: IntArray): Int {
        val hist = IntArray(256)
        v.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var t = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                t = i
            }
        }
        return t
    }

    private fun percentiles(v: IntArray, lo: Float, hi: Float): Pair<Int, Int> {
        val hist = IntArray(256)
        v.forEach { hist[it.coerceIn(0, 255)]++ }
        fun at(f: Float): Int {
            val target = (v.size * f).toInt()
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc > target) return i
            }
            return 255
        }
        return at(lo) to at(hi)
    }
}
