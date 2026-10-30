package dev.medriid.davit.ui.map

import dev.medriid.davit.map.CampusMap
import dev.medriid.davit.map.Passage
import dev.medriid.davit.map.Passages
import dev.medriid.davit.map.Poi
import dev.medriid.davit.map.Ring
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A polygon or line in metres east (x) and north (y) of the campus centre. */
class Shape(val x: FloatArray, val y: FloatArray) {
    val size: Int get() = x.size
    val cx: Float
    val cy: Float
    /** Twice the signed area: positive when the points go anticlockwise. */
    val turn: Float

    init {
        var sx = 0f
        var sy = 0f
        var a = 0f
        for (i in x.indices) {
            sx += x[i]
            sy += y[i]
            val j = (i + 1) % x.size
            a += x[i] * y[j] - x[j] * y[i]
        }
        cx = if (x.isEmpty()) 0f else sx / x.size
        cy = if (y.isEmpty()) 0f else sy / y.size
        turn = a
    }

    fun contains(px: Float, py: Float): Boolean {
        var inside = false
        var j = size - 1
        for (i in 0 until size) {
            if ((y[i] > py) != (y[j] > py) && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) inside = !inside
            j = i
        }
        return inside
    }
}

class SceneBuilding(
    val index: Int,
    val name: String,
    val cat: String,
    val shape: Shape,
    val holes: List<Shape>,
    val height: Float,
    val levels: Int,
    /** Openings in the outer walls where a way runs through: archways, passages. */
    val arches: List<Passage> = emptyList(),
) {
    val footprint: Float = abs(shape.turn) / 2
}

class SceneLine(val kind: String, val shape: Shape)

class ScenePoi(val poi: Poi, val x: Float, val y: Float)

/**
 * The campus, projected once into metres around its centre (an equirectangular
 * projection is exact enough across two kilometres), ready to draw at any angle.
 */
class Scene(val map: CampusMap) {
    private val lat0 = map.meta.centreLat
    private val lon0 = map.meta.centreLon
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_574.0

    fun x(lon: Double) = ((lon - lon0) * kx).toFloat()
    fun y(lat: Double) = ((lat - lat0) * ky).toFloat()
    fun lon(x: Float) = lon0 + x / kx
    fun lat(y: Float) = lat0 + y / ky

    private fun shape(r: Ring): Shape {
        // Drop a closing point that repeats the first.
        val n = if (r.size > 2 && r.lon[0] == r.lon[r.size - 1] && r.lat[0] == r.lat[r.size - 1]) r.size - 1 else r.size
        return Shape(FloatArray(n) { x(r.lon[it]) }, FloatArray(n) { y(r.lat[it]) })
    }

    private fun line(r: Ring) = Shape(FloatArray(r.size) { x(r.lon[it]) }, FloatArray(r.size) { y(r.lat[it]) })

    val boundary = map.boundary.map { shape(it.outer) }
    val green = map.green.map { shape(it.outer) }
    val water = map.water.map { shape(it.outer) }
    val roads = map.roads.map { SceneLine(it.kind, line(it.line)) }
    val paths = map.paths.map { SceneLine(it.kind, line(it.line)) }
    val walls = (map.walls + map.rail).map { SceneLine(it.kind, line(it.line)) }

    /** Storeys are about 3.3 m; a PRP block uses its own floor count. Ways through a building cut archways in it. */
    val buildings = run {
        val arches = Passages.find(map).filter { it.ring == 0 }.groupBy { it.building }
        map.buildings.mapIndexed { i, b ->
            SceneBuilding(i, b.name, b.cat, shape(b.outer), b.holes.map(::shape), b.shownLevels * 3.3f, b.shownLevels, arches[i].orEmpty())
        }
    }

    /** PRP blocks are drawn as their own buildings (the classroom maze), on top of the park's outline. */
    val prpBlocks = map.prp.map { p -> Triple(p, shape(p.ring), (p.floors.size.coerceAtLeast(1)) * 3.3f) }

    val pois = map.pois.map { ScenePoi(it, x(it.lon), y(it.lat)) }

    /** Campus extent in metres: west, south, east, north. */
    val extent: FloatArray = run {
        val xs = boundary.flatMap { it.x.toList() }.ifEmpty { listOf(x(map.meta.west), x(map.meta.east)) }
        val ys = boundary.flatMap { it.y.toList() }.ifEmpty { listOf(y(map.meta.south), y(map.meta.north)) }
        floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** The building whose footprint holds a ground point, the smallest if they nest. */
    fun buildingAt(px: Float, py: Float): SceneBuilding? =
        buildings.filter { it.shape.contains(px, py) }.minByOrNull { it.footprint }

    fun nearestPoi(px: Float, py: Float, within: Float, filter: (ScenePoi) -> Boolean = { true }): ScenePoi? {
        var best: ScenePoi? = null
        var bestD = within * within
        for (p in pois) {
            if (!filter(p)) continue
            val d = (p.x - px) * (p.x - px) + (p.y - py) * (p.y - py)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best
    }

    val width: Float get() = max(1f, extent[2] - extent[0])
    val height: Float get() = max(1f, extent[3] - extent[1])
    val midX: Float get() = (extent[0] + extent[2]) / 2
    val midY: Float get() = (extent[1] + extent[3]) / 2

    fun clampX(v: Float) = min(extent[2] + 350f, max(extent[0] - 350f, v))
    fun clampY(v: Float) = min(extent[3] + 350f, max(extent[1] - 350f, v))
}
