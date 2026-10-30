package dev.medriid.davit.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** The next change of direction along a route: [metres] ahead, turning [degrees] (positive is right). */
data class Turn(val metres: Float, val degrees: Float) {
    /** "Turn left", "Slight right", "Turn around"… */
    val words: String
        get() {
            val side = if (degrees > 0) "right" else "left"
            return when (abs(degrees)) {
                in 0f..55f -> "Slight $side"
                in 55f..130f -> "Turn $side"
                else -> "Sharp $side"
            }
        }
}

/**
 * Where you are on a route, for following it: the point on it nearest you, how far off it
 * you are, the way it heads there (a compass bearing in radians, clockwise from north, as
 * the map's camera turns), what's left, and the next turn.
 */
data class Progress(
    /** The segment you're on: from point [segment] to the next. */
    val segment: Int,
    /** The nearest point on the route, in the map's metres. */
    val x: Float,
    val y: Float,
    /** How far you are from the route, in metres. */
    val off: Float,
    /** Metres left to the end. */
    val remaining: Float,
    val heading: Float,
    val next: Turn?,
)

/**
 * Following a route drawn as `[x0, y0, x1, y1, …]` in metres east and north. Kinks under
 * [MinTurn] degrees aren't turns, and very short legs are looked past so a wobbly path
 * through a gate doesn't read as three turns.
 */
object Guide {
    const val MinTurn = 30f
    private const val ShortLeg = 4f

    private fun bearing(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2(x1 - x0, y1 - y0)

    /** The signed change from bearing [a] to [b], in degrees within ±180; positive is clockwise (right). */
    fun turn(a: Float, b: Float): Float {
        var d = (b - a) * 180f / PI.toFloat()
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    fun progress(pts: FloatArray, x: Float, y: Float): Progress? {
        val n = pts.size / 2
        if (n < 2) return null
        // The nearest point on any segment.
        var best = Float.MAX_VALUE
        var seg = 0
        var px = pts[0]
        var py = pts[1]
        for (i in 0 until n - 1) {
            val ax = pts[2 * i]; val ay = pts[2 * i + 1]
            val bx = pts[2 * i + 2]; val by = pts[2 * i + 3]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            val qx = ax + t * dx; val qy = ay + t * dy
            val d = hypot(x - qx, y - qy)
            if (d < best) {
                best = d; seg = i; px = qx; py = qy
            }
        }
        // What's left: the rest of this segment, then every one after it.
        var remaining = hypot(pts[2 * seg + 2] - px, pts[2 * seg + 3] - py)
        for (i in seg + 1 until n - 1) remaining += hypot(pts[2 * i + 2] - pts[2 * i], pts[2 * i + 3] - pts[2 * i + 1])
        val heading = bearing(pts[2 * seg], pts[2 * seg + 1], pts[2 * seg + 2], pts[2 * seg + 3])
        return Progress(seg, px, py, best, remaining, heading, nextTurn(pts, seg, px, py))
    }

    /** The first real turn after the point ([px], [py]) on segment [seg]. */
    private fun nextTurn(pts: FloatArray, seg: Int, px: Float, py: Float): Turn? {
        val n = pts.size / 2
        var dist = hypot(pts[2 * seg + 2] - px, pts[2 * seg + 3] - py)
        var dir = bearing(pts[2 * seg], pts[2 * seg + 1], pts[2 * seg + 2], pts[2 * seg + 3])
        var i = seg + 1
        while (i < n - 1) {
            // The next leg long enough to say which way the path goes.
            var j = i
            var len = 0f
            while (j < n - 1) {
                len = hypot(pts[2 * j + 2] - pts[2 * i], pts[2 * j + 3] - pts[2 * i + 1])
                if (len >= ShortLeg) break
                j++
            }
            if (j >= n - 1 && len < ShortLeg) break
            val out = bearing(pts[2 * i], pts[2 * i + 1], pts[2 * j + 2], pts[2 * j + 3])
            val change = turn(dir, out)
            if (abs(change) >= MinTurn) return Turn(dist, change)
            for (k in i..j) dist += hypot(pts[2 * k + 2] - pts[2 * k], pts[2 * k + 3] - pts[2 * k + 1])
            dir = out
            i = j + 1
        }
        return null
    }

    /** The route from your nearest point on it to the end, for drawing what's left. */
    fun ahead(pts: FloatArray, p: Progress): FloatArray {
        val rest = pts.copyOfRange(2 * (p.segment + 1), pts.size)
        return floatArrayOf(p.x, p.y) + rest
    }
}
