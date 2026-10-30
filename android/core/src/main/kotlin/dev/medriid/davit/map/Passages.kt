package dev.medriid.davit.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Where a path or road runs through a building: an archway, a passage under it, or a
 * corridor through its ground floor. Each is one wall crossing, so the map can cut an
 * opening into that wall.
 */
data class Passage(
    /** Index into [CampusMap.buildings]. */
    val building: Int,
    /** 0 for the outer wall, 1+ for a courtyard's. */
    val ring: Int,
    /** The wall: from point [edge] of that ring to the next (a repeated closing point dropped). */
    val edge: Int,
    /** How far along that wall, 0 to 1. */
    val at: Double,
    /** Half the opening's width, metres. */
    val halfWidth: Double,
)

/** A building's wall ring in metres. */
private class Wall(val ring: Int, val x: DoubleArray, val y: DoubleArray) {
    fun length(e: Int): Double = hypot(x[(e + 1) % x.size] - x[e], y[(e + 1) % y.size] - y[e])
}

object Passages {
    /** Shorter than this inside a building, a crossing is a mapping wobble at a corner, not a way through. */
    private const val MIN_INSIDE = 2.5

    /** Openings closer than this on one wall are the same one. */
    private const val SAME = 3.0

    /**
     * Every opening: a way that enters a building and comes out again (through a wall or
     * into a courtyard), and ways that meet inside one, which together go through it.
     */
    fun find(map: CampusMap): List<Passage> {
        val lat0 = map.meta.centreLat
        val lon0 = map.meta.centreLon
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_574.0
        fun px(lon: Double) = (lon - lon0) * kx
        fun py(lat: Double) = (lat - lat0) * ky

        fun wall(index: Int, r: Ring): Wall {
            val n = if (r.size > 2 && r.lon[0] == r.lon[r.size - 1] && r.lat[0] == r.lat[r.size - 1]) r.size - 1 else r.size
            return Wall(index, DoubleArray(n) { px(r.lon[it]) }, DoubleArray(n) { py(r.lat[it]) })
        }

        // Ways as lines in metres, with how wide an opening each needs.
        class Line(val x: DoubleArray, val y: DoubleArray, val half: Double)
        val lines = ArrayList<Line>()
        for (w in map.roads + map.paths) {
            val r = w.line
            if (r.size < 2) continue
            val half = when (w.kind) {
                "footway", "path", "steps", "pedestrian", "corridor", "cycleway" -> 1.9
                else -> 3.0
            }
            lines += Line(DoubleArray(r.size) { px(r.lon[it]) }, DoubleArray(r.size) { py(r.lat[it]) }, half)
        }
        // The walking network too: its links can join two ways inside a building.
        map.graph?.let { g ->
            for (e in 0 until g.edges) {
                val (a, b) = g.ends(e)
                lines += Line(doubleArrayOf(px(g.lon[a]), px(g.lon[b])), doubleArrayOf(py(g.lat[a]), py(g.lat[b])), 1.9)
            }
        }

        val out = ArrayList<Passage>()
        map.buildings.forEachIndexed { bi, b ->
            val walls = listOf(wall(0, b.outer)) + b.holes.mapIndexed { i, h -> wall(i + 1, h) }
            val outer = walls[0]
            if (outer.x.size < 3) return@forEachIndexed
            val bx0 = outer.x.min() - 1; val bx1 = outer.x.max() + 1
            val by0 = outer.y.min() - 1; val by1 = outer.y.max() + 1
            fun inside(x: Double, y: Double): Boolean =
                contains(outer.x, outer.y, x, y) && walls.drop(1).none { contains(it.x, it.y, x, y) }

            val found = ArrayList<Passage>()
            val doors = ArrayList<Passage>()
            for (l in lines) {
                if (l.x.max() < bx0 || l.x.min() > bx1 || l.y.max() < by0 || l.y.min() > by1) continue
                // Crossings in order along the line: distance along it, and where on which wall.
                class Cross(val along: Double, val p: Passage)
                val crosses = ArrayList<Cross>()
                var run = 0.0
                for (i in 0 until l.x.size - 1) {
                    val ax = l.x[i]; val ay = l.y[i]; val dx = l.x[i + 1] - ax; val dy = l.y[i + 1] - ay
                    val len = hypot(dx, dy)
                    for (w in walls) {
                        val n = w.x.size
                        for (e in 0 until n) {
                            val ex = w.x[e]; val ey = w.y[e]
                            val fx = w.x[(e + 1) % n] - ex; val fy = w.y[(e + 1) % n] - ey
                            val den = dx * fy - dy * fx
                            if (abs(den) < 1e-9) continue
                            val s = ((ex - ax) * fy - (ey - ay) * fx) / den
                            val t = ((ex - ax) * dy - (ey - ay) * dx) / den
                            if (s < 0 || s > 1 || t < 0 || t > 1) continue
                            crosses += Cross(run + s * len, Passage(bi, w.ring, e, t, l.half))
                        }
                    }
                    run += len
                }
                if (crosses.isEmpty()) continue
                crosses.sortBy { it.along }
                var isIn = inside(l.x[0], l.y[0])
                var entered: Cross? = null
                for (c in crosses) {
                    if (!isIn) {
                        entered = c
                    } else {
                        val e = entered
                        if (e != null) {
                            if (c.along - e.along >= MIN_INSIDE) {
                                found += e.p
                                found += c.p
                            }
                        } else if (c.along >= MIN_INSIDE) {
                            // Started inside and came out: half of a way that meets another indoors.
                            doors += c.p
                        }
                        entered = null
                    }
                    isIn = !isIn
                }
                // Ended inside: the other half.
                val e = entered
                if (isIn && e != null && run - e.along >= MIN_INSIDE) doors += e.p
            }
            // Ways that meet inside go through together: at least two doors, apart.
            if (doors.size >= 2) {
                val apart = doors.any { a -> doors.any { c -> a.ring != c.ring || a.edge != c.edge || abs(a.at - c.at) * edgeLength(walls, a) > SAME * 2 } }
                if (apart) found += doors
            }
            // One opening per spot on a wall.
            for (p in found) {
                if (out.none { q -> q.building == p.building && q.ring == p.ring && q.edge == p.edge && abs(q.at - p.at) * edgeLength(walls, p) < SAME }) out += p
            }
        }
        return out
    }

    private fun edgeLength(walls: List<Wall>, p: Passage): Double = walls[p.ring].length(p.edge)

    private fun contains(xs: DoubleArray, ys: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside
            j = i
        }
        return inside
    }
}
