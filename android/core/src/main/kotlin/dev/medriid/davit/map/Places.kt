package dev.medriid.davit.map

/**
 * Turns what VTOP calls a room ("SJT-401", "PRP327", "TT G04", a cabin "SJT 411 A32")
 * into a place on the map, with the floor when the room number says it.
 */
object Places {

    /** Where a venue is: the point to route to, what to call it, and the floor if known. */
    data class Spot(
        val lat: Double,
        val lon: Double,
        val title: String,
        val building: String,
        val room: String,
        val floor: String?,
        val poi: Poi?,
        val prp: PrpBlock?,
    )

    /** Codes VTOP uses that the map's names don't spell out. */
    private val Extra = mapOf(
        "MB" to "Main Building",
        "MGR" to "MGB",
        "MGB" to "MGB",
        "CDMR" to "CDMM",
        "GDN" to "GDN",
        "SJT" to "SJT",
        "TT" to "TT",
        "SMV" to "SMV",
        "PRP" to "PRP",
        "LIB" to "EV Periyar Library",
        "ANNA" to "Anna Auditorium",
    )

    /**
     * Does a PRP range like "G29–41" or "327–341" (or "610") hold [room] ("G35", "330")?
     * A ground-floor room only matches a G range, and the other way round.
     */
    fun roomInRange(range: String?, room: String): Boolean {
        if (range.isNullOrBlank()) return false
        val r = room.trim().uppercase()
        val ground = r.startsWith("G")
        val n = r.removePrefix("G").toIntOrNull() ?: return false
        if (range.trim().startsWith("G", ignoreCase = true) != ground) return false
        val nums = Regex("""\d+""").findAll(range).map { it.value.toInt() }.toList()
        return when (nums.size) {
            0 -> false
            1 -> nums[0] == n
            else -> n in nums[0]..nums[1]
        }
    }

    /** "4th floor" from "401", "Ground" from "G04"; null when the room doesn't say. */
    fun floorOf(room: String): String? {
        val r = room.trim().uppercase()
        if (r.startsWith("G")) return "Ground floor"
        val digits = r.takeWhile(Char::isDigit)
        val f = when {
            digits.length == 3 -> digits.take(1).toInt()
            digits.length == 4 -> digits.take(2).toInt()
            else -> return null
        }
        return when (f) {
            0 -> "Ground floor"
            1 -> "1st floor"
            2 -> "2nd floor"
            3 -> "3rd floor"
            else -> "${f}th floor"
        }
    }

    /** The building codes this map knows (from names and aliases), longest first, for prefix matching. */
    fun codes(map: CampusMap): List<String> =
        (map.pois.flatMap { listOf(it.name) + it.aliases } + Extra.keys)
            .map { it.uppercase().replace(Regex("[^A-Z0-9]"), "") }
            .filter { it.length in 2..8 && it.first().isLetter() }
            .distinct()
            .sortedByDescending { it.length }

    /**
     * Resolves a venue. Splits it into a building code and a room, finds the building
     * (exact names and aliases first), and for PRP finds the block whose floor holds the room.
     */
    fun resolve(map: CampusMap, venue: String, codes: List<String> = codes(map)): Spot? {
        val compact = venue.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (compact.isEmpty()) return null
        // The longest known code the venue starts with, followed by a room (or nothing).
        val code = codes.firstOrNull { c ->
            compact.startsWith(c) && compact.substring(c.length).let { rest -> rest.isEmpty() || rest.first().isDigit() || (rest.first() == 'G' && rest.length > 1 && rest[1].isDigit()) }
        } ?: return null
        val room = compact.substring(code.length).let { r ->
            // "411A32" (a cabin) → room 411.
            Regex("""^(G?\d+)""").find(r)?.value.orEmpty()
        }
        val target = Extra[code] ?: code

        if (target == "PRP" && room.isNotEmpty()) {
            for (block in map.prp) {
                val floor = block.floors.firstOrNull { roomInRange(it.rooms, room) } ?: continue
                val (lon, lat) = block.ring.centre()
                val poi = map.pois.firstOrNull { it.cat == "prp" && it.letter.equals(block.letter, ignoreCase = true) }
                return Spot(lat, lon, "${block.name} · room $room", block.name, room, floor.label, poi, block)
            }
        }
        val poi = findPoi(map, target) ?: return null
        val floor = if (room.isNotEmpty()) floorOf(room) else null
        return Spot(poi.lat, poi.lon, if (room.isNotEmpty()) "${poi.name} $room" else poi.name, poi.name, room, floor, poi, null)
    }

    /** A place by exact name or alias, preferring academic buildings over shops of the same name. */
    fun findPoi(map: CampusMap, name: String): Poi? {
        fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val n = norm(name)
        val matches = map.pois.filter { p -> norm(p.name) == n || p.aliases.any { norm(it) == n } }
        val rank = listOf("academic", "prp", "lecture", "admin")
        return matches.minByOrNull { p -> rank.indexOf(p.cat).let { if (it < 0) rank.size else it } }
            ?: map.pois.firstOrNull { norm(it.name).startsWith(n) && n.length >= 3 }
    }

    /** The footprint a place stands in (for lighting it up on the map), or null. */
    fun buildingAt(map: CampusMap, lat: Double, lon: Double): Building? =
        map.buildings.filter { contains(it.outer, lat, lon) }.minByOrNull { area(it.outer) }

    fun contains(r: Ring, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = r.size - 1
        for (i in 0 until r.size) {
            if ((r.lat[i] > lat) != (r.lat[j] > lat) &&
                lon < (r.lon[j] - r.lon[i]) * (lat - r.lat[i]) / (r.lat[j] - r.lat[i]) + r.lon[i]
            ) inside = !inside
            j = i
        }
        return inside
    }

    private fun area(r: Ring): Double {
        var s = 0.0
        for (i in 0 until r.size) {
            val j = (i + 1) % r.size
            s += r.lon[i] * r.lat[j] - r.lon[j] * r.lat[i]
        }
        return kotlin.math.abs(s) / 2
    }
}
