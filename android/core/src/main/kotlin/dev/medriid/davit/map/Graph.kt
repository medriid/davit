package dev.medriid.davit.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class Mode { Walk, Cycle }

/** A route: its points (start and end included), length, time and what it goes through. */
class Route(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val metres: Int,
    val seconds: Int,
    val steps: Boolean,
    val indoor: Boolean,
    val unpaved: Boolean,
)

/**
 * The campus walking and cycling network from graph.json: node coordinates and
 * edges `[a, b, metres, walk seconds, cycle seconds (-1 = not allowed), flags]`, flags
 * 1 steps, 2 indoor, 4 lit, 8 unpaved shortcut. Edges go both ways. Routes are A* on the
 * chosen mode's seconds.
 */
class Graph(
    val lat: DoubleArray,
    val lon: DoubleArray,
    private val a: IntArray,
    private val b: IntArray,
    private val metres: FloatArray,
    private val walk: FloatArray,
    private val cycle: FloatArray,
    private val flags: IntArray,
) {
    val nodes: Int get() = lat.size
    val edges: Int get() = a.size

    /** The two nodes edge [e] joins. */
    fun ends(e: Int): Pair<Int, Int> = a[e] to b[e]

    // Compressed adjacency: for node n, neighbours are to[head[n] until head[n + 1]] via edge edgeOf[...].
    private val head = IntArray(nodes + 1)
    private val to: IntArray
    private val edgeOf: IntArray

    /** Fastest speed seen per mode (metres per second), so the A* estimate never overshoots. */
    private val top = FloatArray(2)

    init {
        val degree = IntArray(nodes)
        for (e in a.indices) {
            degree[a[e]]++
            degree[b[e]]++
        }
        for (n in 0 until nodes) head[n + 1] = head[n] + degree[n]
        to = IntArray(head[nodes])
        edgeOf = IntArray(head[nodes])
        val fill = head.copyOf()
        for (e in a.indices) {
            to[fill[a[e]]] = b[e]; edgeOf[fill[a[e]]++] = e
            to[fill[b[e]]] = a[e]; edgeOf[fill[b[e]]++] = e
        }
        for (e in a.indices) {
            if (walk[e] > 0) top[0] = max(top[0], metres[e] / walk[e])
            if (cycle[e] > 0) top[1] = max(top[1], metres[e] / cycle[e])
        }
        if (top[0] <= 0f) top[0] = 1.4f
        if (top[1] <= 0f) top[1] = 4.5f
    }

    /** The node closest to a point (a plain scan: under a thousand nodes). */
    fun nearest(atLat: Double, atLon: Double): Int {
        val k = cos(Math.toRadians(atLat))
        var best = -1
        var bestD = Double.MAX_VALUE
        for (n in 0 until nodes) {
            val dy = lat[n] - atLat
            val dx = (lon[n] - atLon) * k
            val d = dy * dy + dx * dx
            if (d < bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    /**
     * Fastest way from one point to another. The bits between each point and its nearest
     * node are added at walking pace, as a straight line. Null if they aren't connected.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: Mode): Route? {
        val s = nearest(fromLat, fromLon)
        val t = nearest(toLat, toLon)
        if (s < 0 || t < 0) return null
        val cost = if (mode == Mode.Walk) walk else cycle
        val speed = top[mode.ordinal]
        val dist = DoubleArray(nodes) { Double.POSITIVE_INFINITY }
        val via = IntArray(nodes) { -1 }
        val done = BooleanArray(nodes)
        val queue = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[s] = 0.0
        queue += metresBetween(lat[s], lon[s], lat[t], lon[t]) / speed to s
        while (queue.isNotEmpty()) {
            val (_, n) = queue.poll()
            if (n == t) break
            if (done[n]) continue
            done[n] = true
            for (k in head[n] until head[n + 1]) {
                val e = edgeOf[k]
                val c = cost[e]
                if (c < 0) continue
                val m = to[k]
                val d = dist[n] + c
                if (d < dist[m]) {
                    dist[m] = d
                    via[m] = k
                    queue += d + metresBetween(lat[m], lon[m], lat[t], lon[t]) / speed to m
                }
            }
        }
        if (dist[t].isInfinite()) return null
        val nodesOnPath = ArrayList<Int>()
        var steps = false
        var indoor = false
        var unpaved = false
        var length = 0.0
        var n = t
        nodesOnPath += n
        while (n != s) {
            val k = via[n]
            val e = edgeOf[k]
            length += metres[e]
            if (flags[e] and 1 != 0) steps = true
            if (flags[e] and 2 != 0) indoor = true
            if (flags[e] and 8 != 0) unpaved = true
            n = if (a[e] == n) b[e] else a[e]
            nodesOnPath += n
        }
        nodesOnPath.reverse()
        val startGap = metresBetween(fromLat, fromLon, lat[s], lon[s])
        val endGap = metresBetween(toLat, toLon, lat[t], lon[t])
        val outLat = DoubleArray(nodesOnPath.size + 2)
        val outLon = DoubleArray(nodesOnPath.size + 2)
        outLat[0] = fromLat
        outLon[0] = fromLon
        nodesOnPath.forEachIndexed { i, node ->
            outLat[i + 1] = lat[node]
            outLon[i + 1] = lon[node]
        }
        outLat[outLat.size - 1] = toLat
        outLon[outLon.size - 1] = toLon
        return Route(
            outLat, outLon,
            (length + startGap + endGap).roundToInt(),
            (dist[t] + (startGap + endGap) / WALK_SPEED).roundToInt(),
            steps, indoor, unpaved,
        )
    }

    companion object {
        /** Walking pace for the bits off the network, metres per second. */
        const val WALK_SPEED = 1.35

        fun parse(o: JsonObject): Graph {
            fun nums(key: String) = (o[key] as JsonArray).map { (it as JsonPrimitive).doubleOrNull ?: 0.0 }.toDoubleArray()
            val edges = (o["edges"] as JsonArray).map { e -> (e as JsonArray).map { (it as JsonPrimitive).doubleOrNull ?: 0.0 } }
                .filter { it.size >= 5 }
            return Graph(
                nums("lat"), nums("lon"),
                IntArray(edges.size) { edges[it][0].toInt() },
                IntArray(edges.size) { edges[it][1].toInt() },
                FloatArray(edges.size) { edges[it][2].toFloat() },
                FloatArray(edges.size) { edges[it][3].toFloat() },
                FloatArray(edges.size) { edges[it][4].toFloat() },
                IntArray(edges.size) { edges[it].getOrElse(5) { 0.0 }.toInt() },
            )
        }

        /** Great-circle distance in metres. */
        fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
