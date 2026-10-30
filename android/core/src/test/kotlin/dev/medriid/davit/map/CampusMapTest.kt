package dev.medriid.davit.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map data reader, routing and venue resolving, on a tiny made-up campus in the map files' shape. */
class CampusMapTest {
    private fun res(name: String) = javaClass.getResource("/map/$name")!!.readText()
    private val map = CampusMapParser.parse(res("campus.json"), res("geo.json"), res("graph.json"))

    @Test
    fun readsEveryLayer() {
        assertEquals("Test Campus", map.meta.name)
        assertEquals(79.16, map.meta.centreLon, 1e-9)
        // pois plus the places list, each once.
        assertEquals(listOf("sjt", "tt", "shop-tt", "prp-e", "mh-veg", "gate-1"), map.pois.map { it.id })
        assertEquals(listOf("Main Gate"), map.pois.last().aliases)
        assertEquals(2, map.buildings.size)
        assertEquals(9, map.buildings[0].levels)
        assertEquals(9, map.buildings[0].shownLevels)
        // No levels mapped: a hostel is drawn six floors high; the courtyard is a hole.
        assertNull(map.buildings[1].levels)
        assertEquals(6, map.buildings[1].shownLevels)
        assertEquals(1, map.buildings[1].holes.size)
        assertEquals(1, map.green.size)
        assertEquals(1, map.water.size)
        assertEquals(1, map.boundary.size)
        assertEquals("residential", map.roads.single().kind)
        assertTrue(map.roads.single().lit)
        assertEquals(listOf("footway", "steps"), map.paths.map { it.kind })
        assertEquals(1, map.walls.size)
        assertEquals(listOf("E", "A"), map.prp.map { it.letter })
        assertEquals("PRP classrooms", map.category("prp"))
        assertEquals("Shop", map.category("shop"))
        assertEquals(4, map.graph!!.nodes)
    }

    @Test
    fun menusPickTheDateElseTheLatestSameWeekday() {
        val today = map.menusFor("MH Veg Mess", "2026-10-29", "Thursday")
        assertEquals(listOf("Breakfast", "Dinner"), today.map { it.meal })
        // A later Thursday with no data falls back to the latest Thursday on file.
        assertEquals(listOf("Breakfast", "Dinner"), map.menusFor("MH Veg Mess", "2026-11-05", "Thursday").map { it.meal })
        assertTrue(map.menusFor("MH Veg Mess", "2026-11-06", "Friday").isEmpty())
    }

    @Test
    fun walkingTakesTheStepsButBikesGoRound() {
        val g = map.graph!!
        val walk = g.route(12.9700, 79.1600, 12.9710, 79.1620, Mode.Walk)!!
        assertEquals(120, walk.seconds)
        assertEquals(230, walk.metres)
        assertTrue(walk.steps)
        assertEquals(listOf(12.97, 12.97, 12.971, 12.971), walk.lat.toList())
        val bike = g.route(12.9700, 79.1600, 12.9710, 79.1620, Mode.Cycle)!!
        assertEquals(73, bike.seconds)
        assertEquals(327, bike.metres)
        assertFalse(bike.steps)
        assertEquals(6, bike.lat.size)
    }

    @Test
    fun offTheNetworkIsAddedAtWalkingPace() {
        val g = map.graph!!
        // About 11 m south of node 0, to node 1 exactly.
        val r = g.route(12.9699, 79.1600, 12.9700, 79.1610, Mode.Walk)!!
        assertEquals(119, r.metres)
        assertEquals(88, r.seconds)
        assertEquals(1, g.nearest(12.97001, 79.16099))
    }

    @Test
    fun prpRoomsFindTheirBlockAndFloor() {
        assertTrue(Places.roomInRange("327–341", "330"))
        assertTrue(Places.roomInRange("G29–41", "g35"))
        assertFalse(Places.roomInRange("G29–41", "35"))
        assertFalse(Places.roomInRange("327–341", "G30"))
        assertTrue(Places.roomInRange("610", "610"))
        assertFalse(Places.roomInRange(null, "610"))

        val e = Places.resolve(map, "PRP-327")!!
        assertEquals("PRP Block E", e.building)
        assertEquals("3rd floor", e.floor)
        assertEquals("prp-e", e.poi?.id)
        val a = Places.resolve(map, "PRP350")!!
        assertEquals("PRP Block A", a.building)
        assertEquals("G35", Places.resolve(map, "PRP G35")!!.room)
    }

    @Test
    fun venuesAndCabinsFindBuildings() {
        val sjt = Places.resolve(map, "SJT-401")!!
        assertEquals("SJT", sjt.building)
        assertEquals("401", sjt.room)
        assertEquals("4th floor", sjt.floor)
        assertEquals("SJT 401", sjt.title)
        // A cabin: building, room, then the cabin's own letters.
        assertEquals("SJT", Places.resolve(map, "SJT 411 A32")!!.building)
        // An academic building wins over a shop that shares its letters.
        assertEquals("tt", Places.resolve(map, "TT G04")!!.poi?.id)
        assertEquals("Ground floor", Places.resolve(map, "TT G04")!!.floor)
        assertNull(Places.resolve(map, "XYZ-101"))
        assertNull(Places.resolve(map, ""))
        assertNotNull(Places.findPoi(map, "Main Gate"))
    }

    @Test
    fun floorsFromRoomNumbers() {
        assertEquals("Ground floor", Places.floorOf("G04"))
        assertEquals("1st floor", Places.floorOf("101"))
        assertEquals("11th floor", Places.floorOf("1102"))
        assertNull(Places.floorOf("12"))
    }

    @Test
    fun buildingsContainTheirPoints() {
        assertEquals("Silver Jubilee Tower", Places.buildingAt(map, 12.9710, 79.1640)?.name)
        assertNull(Places.buildingAt(map, 12.9650, 79.1500))
    }

    @Test
    fun waysThroughBuildingsBecomeArchways() {
        fun path(vararg pts: Pair<Double, Double>) =
            """{"type": "Feature", "properties": {"name": "", "hw": "footway", "lit": "", "surface": "", "tunnel": ""}, "geometry": {"type": "LineString", "coordinates": [""" +
                pts.joinToString { "[${it.first}, ${it.second}]" } + "]}},"
        val extra = listOf(
            // Straight through Silver Jubilee Tower, west wall to east wall.
            path(79.1636 to 12.9710, 79.1644 to 12.9710),
            // Up to its south wall and in a little: a doorway, not a way through.
            path(79.1640 to 12.9705, 79.1640 to 12.9709),
            // Into Courtyard Hall's courtyard through the south wing.
            path(79.1605 to 12.9715, 79.1605 to 12.9725),
        ).joinToString("")
        val geo = res("geo.json").replace("\"paths\": {\"type\": \"FeatureCollection\", \"features\": [", "\"paths\": {\"type\": \"FeatureCollection\", \"features\": [$extra")
        val m = CampusMapParser.parse(res("campus.json"), geo, res("graph.json"))
        val ps = Passages.find(m)
        // Tower: the west wall (edge 3) and the east wall (edge 1), halfway along each.
        assertEquals(listOf(1, 3), ps.filter { it.building == 0 }.map { it.edge }.sorted())
        ps.filter { it.building == 0 }.forEach { assertEquals(0.5, it.at, 0.01); assertEquals(0, it.ring) }
        // Hall: the outer south wall and the courtyard's south wall.
        assertEquals(listOf(0 to 0, 1 to 0), ps.filter { it.building == 1 }.map { it.ring to it.edge }.sortedBy { it.first })
    }
}
