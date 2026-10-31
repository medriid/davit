package dev.medriid.davit.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A ring or line of points in degrees, kept as two arrays (cheap to project every frame). */
class Ring(val lon: DoubleArray, val lat: DoubleArray) {
    val size: Int get() = lon.size

    /** Mean of the points: good enough for placing a label or a pin on a footprint. */
    fun centre(): Pair<Double, Double> {
        val n = if (size > 1 && lon[0] == lon[size - 1] && lat[0] == lat[size - 1]) size - 1 else size
        if (n == 0) return 0.0 to 0.0
        var x = 0.0
        var y = 0.0
        for (i in 0 until n) {
            x += lon[i]
            y += lat[i]
        }
        return x / n to y / n
    }
}

data class Building(
    val name: String,
    val cat: String,
    val levels: Int?,
    val outer: Ring,
    val holes: List<Ring>,
    val construction: Boolean,
) {
    /** Floors to raise it by: OSM's count when mapped, else a guess by what it is. */
    val shownLevels: Int
        get() = levels ?: when (cat) {
            "hostel" -> 6
            "academic" -> 4
            "prp" -> 8
            "admin" -> 3
            "lecture", "sports" -> 2
            else -> 1
        }
}

data class Area(val kind: String, val outer: Ring, val holes: List<Ring>)

/** A road, path, wall or railway: OSM's highway kind ("footway", "steps", "residential"…). */
data class Way(val kind: String, val name: String, val line: Ring, val lit: Boolean, val tunnel: Boolean)

data class Floor(val level: String, val label: String, val rooms: String?)

data class Poi(
    val id: String,
    val name: String,
    val cat: String,
    val lat: Double,
    val lon: Double,
    val aliases: List<String> = emptyList(),
    val kind: String = "",
    val desc: String = "",
    val hours: String = "",
    val near: String = "",
    val anchor: String = "",
    val floors: List<Floor> = emptyList(),
    val letter: String = "",
    val operator: String = "",
    val phone: String = "",
    val unnamed: Boolean = false,
)

data class PrpBlock(val id: String, val name: String, val letter: String, val color: String, val floors: List<Floor>, val ring: Ring)

data class Category(val id: String, val label: String, val color: String, val pin: Boolean)

data class Menu(val hall: String, val day: String, val date: String, val meal: String, val menu: String)

data class MapMeta(
    val name: String,
    val built: String,
    val centreLon: Double,
    val centreLat: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val attribution: String,
)

/** Everything the campus map draws and searches, from the three public data files. */
class CampusMap(
    val meta: MapMeta,
    val categories: Map<String, Category>,
    val pois: List<Poi>,
    val buildings: List<Building>,
    val green: List<Area>,
    val water: List<Area>,
    val boundary: List<Area>,
    val roads: List<Way>,
    val paths: List<Way>,
    val walls: List<Way>,
    val rail: List<Way>,
    val prp: List<PrpBlock>,
    val menus: List<Menu>,
    val graph: Graph?,
) {
    fun category(id: String): String = categories[id]?.label ?: id.replaceFirstChar { it.uppercase() }

    /** The menus for [hall] on [date] ("2026-10-29"), else the latest of that weekday, breakfast to dinner. */
    fun menusFor(hall: String, date: String, weekday: String): List<Menu> {
        val forHall = menus.filter { it.hall.equals(hall, ignoreCase = true) }
        val exact = forHall.filter { it.date == date }
        val day = exact.ifEmpty {
            val latest = forHall.filter { it.day.equals(weekday, ignoreCase = true) }.maxOfOrNull { it.date } ?: return emptyList()
            forHall.filter { it.date == latest }
        }
        return day.sortedBy { MealOrder.indexOf(it.meal.lowercase()).let { i -> if (i < 0) 9 else i } }
    }

    companion object {
        private val MealOrder = listOf("breakfast", "lunch", "snacks", "dinner")
    }
}

/**
 * Reads the map's campus.json, geo.json and graph.json. Tolerant: a missing layer or
 * field becomes empty rather than failing the lot.
 */
object CampusMapParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Names the map's data has wrong, put right as it's read. */
    private val Fixes = listOf("Perl Research Park" to "Pearl Research Park")

    private fun String.fixed(): String = Fixes.fold(this) { s, (wrong, right) -> s.replace(wrong, right) }

    fun parse(campus: String, geo: String, graph: String?): CampusMap {
        val c = json.parseToJsonElement(campus.fixed()).jsonObject
        val g = json.parseToJsonElement(geo.fixed()).jsonObject
        val meta = c.obj("meta")
        val bounds = meta?.get("bounds")?.arr()
        val centre = meta?.get("center")?.arr()
        val m = MapMeta(
            name = meta.str("name"),
            built = meta.str("built"),
            centreLon = centre?.getOrNull(0).num() ?: 79.1607,
            centreLat = centre?.getOrNull(1).num() ?: 12.9697,
            west = bounds?.getOrNull(0)?.arr()?.getOrNull(0).num() ?: 79.149,
            south = bounds?.getOrNull(0)?.arr()?.getOrNull(1).num() ?: 12.964,
            east = bounds?.getOrNull(1)?.arr()?.getOrNull(0).num() ?: 79.172,
            north = bounds?.getOrNull(1)?.arr()?.getOrNull(1).num() ?: 12.980,
            attribution = meta.str("attribution").ifEmpty { "© OpenStreetMap contributors (ODbL)" },
        )
        val categories = c.obj("categories")?.mapValues { (id, v) ->
            val o = v as? JsonObject
            Category(id, o.str("label").ifEmpty { id }, o.str("color"), o?.get("pin")?.prim()?.booleanOrNull ?: false)
        }.orEmpty()
        val pois = (c.arr("pois") + (c.obj("places")?.arr("items") ?: emptyList()))
            .mapNotNull { poi(it as? JsonObject) }
            .distinctBy { it.id }
        val prpRings = g.features("prp").associate { f -> f.props.str("id") to f.rings.firstOrNull() }
        val prp = c.obj("prp")?.arr("blocks").orEmpty().mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")
            val ring = o["ring"]?.arr()?.let(::ring) ?: prpRings[id] ?: return@mapNotNull null
            PrpBlock(id, o.str("name"), o.str("letter"), o.str("color"), floors(o), ring)
        }
        val menus = c.obj("mess")?.arr("items").orEmpty().mapNotNull { it as? JsonObject }.map {
            Menu(it.str("hall"), it.str("day"), it.str("date"), it.str("meal"), it.str("menu"))
        }
        return CampusMap(
            meta = m,
            categories = categories,
            pois = pois,
            buildings = g.features("buildings").mapNotNull { f ->
                val rings = f.rings
                val outer = rings.firstOrNull() ?: return@mapNotNull null
                Building(
                    f.props.str("name"), f.props.str("cat"), f.props?.get("levels")?.prim()?.intOrNull,
                    outer, rings.drop(1), f.props?.get("construction")?.prim()?.booleanOrNull ?: false,
                )
            },
            green = areas(g, "green"),
            water = areas(g, "water"),
            boundary = areas(g, "boundary"),
            roads = ways(g, "roads"),
            paths = ways(g, "paths"),
            walls = ways(g, "wall"),
            rail = ways(g, "rail"),
            prp = prp,
            menus = menus,
            graph = graph?.let { runCatching { Graph.parse(json.parseToJsonElement(it).jsonObject) }.getOrNull() },
        )
    }

    private fun poi(o: JsonObject?): Poi? {
        o ?: return null
        val lat = o["lat"].num() ?: return null
        val lon = o["lon"].num() ?: return null
        val name = o.str("name").ifEmpty { return null }
        return Poi(
            id = o.str("id").ifEmpty { name },
            name = name,
            cat = o.str("cat"),
            lat = lat,
            lon = lon,
            aliases = o["aliases"]?.arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            kind = o.str("kind"),
            desc = o.str("desc"),
            hours = o.str("hours"),
            near = o.str("near"),
            anchor = o.str("anchor"),
            floors = floors(o),
            letter = o.str("letter"),
            operator = o.str("operator"),
            phone = o.str("phone"),
            unnamed = o["unnamed"]?.prim()?.booleanOrNull ?: false,
        )
    }

    private fun floors(o: JsonObject): List<Floor> =
        o["floors"]?.let { it as? JsonArray }.orEmpty().mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            Floor(fo.str("level"), fo.str("label"), fo["rooms"]?.let { r -> (r as? JsonPrimitive)?.takeIf { it.isString }?.content })
        }

    private class Feature(val props: JsonObject?, val geometry: JsonObject?) {
        val type: String get() = geometry.str("type")

        /** Polygon rings, or a LineString as one ring. */
        val rings: List<Ring>
            get() {
                val coords = geometry?.get("coordinates")?.arr() ?: return emptyList()
                return when (type) {
                    "Polygon" -> coords.mapNotNull { r -> r.arr()?.let(::ring) }
                    "MultiPolygon" -> coords.firstOrNull()?.arr().orEmpty().mapNotNull { r -> r.arr()?.let(::ring) }
                    "LineString" -> listOfNotNull(ring(coords))
                    else -> emptyList()
                }
            }
    }

    private fun JsonObject.features(layer: String): List<Feature> =
        obj(layer)?.arr("features").orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            Feature(o["properties"] as? JsonObject, o["geometry"] as? JsonObject)
        }

    private fun areas(g: JsonObject, layer: String): List<Area> = g.features(layer).mapNotNull { f ->
        val rings = f.rings
        Area(f.props.str("kind").ifEmpty { layer }, rings.firstOrNull() ?: return@mapNotNull null, rings.drop(1))
    }

    private fun ways(g: JsonObject, layer: String): List<Way> = g.features(layer).mapNotNull { f ->
        val line = f.rings.firstOrNull()?.takeIf { it.size >= 2 } ?: return@mapNotNull null
        Way(f.props.str("hw").ifEmpty { f.props.str("kind").ifEmpty { layer } }, f.props.str("name"), line, f.props.str("lit") == "yes", f.props.str("tunnel").let { it.isNotEmpty() && it != "no" })
    }

    private fun ring(points: List<JsonElement>): Ring? {
        val pts = points.mapNotNull { p -> p.arr()?.let { a -> a.getOrNull(0).num()?.let { x -> a.getOrNull(1).num()?.let { y -> x to y } } } }
        if (pts.isEmpty()) return null
        return Ring(DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second })
    }

    private fun JsonElement?.arr(): List<JsonElement>? = (this as? JsonArray)?.toList()
    private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): List<JsonElement> = (this?.get(key) as? JsonArray)?.toList().orEmpty()
    private fun JsonObject?.str(key: String): String = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString || it.doubleOrNull != null }?.content.orEmpty()

}
