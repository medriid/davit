package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceDay
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own course names: where they win, and where VTOP's has to stay. */
class AliasesTest {
    private val physics = AttendanceRow("BPHY101", "Engineering Physics", "Embedded Theory", 38, 51, null, "")
    private val physicsLab = AttendanceRow("BPHY102", "Engineering Physics Lab", "Lab Only", 10, 12, null, "")
    private fun snap(changes: List<String> = emptyList()) = Snapshot(
        semester = Semester("S", "Sem"), semesters = emptyList(), attendance = listOf(physics, physicsLab), timetable = emptyList(),
        syncedAt = 0, changes = changes,
        history = mapOf(physics.key to listOf(AttendanceDay("22-Oct-2026", "A1", AttendanceDay.Status.Absent))),
    )

    @Test
    fun yourNameWinsAndBlankFallsBack() {
        val aliases = mapOf("BPHY101" to " Physics ", "BCSE1" to "  ")
        assertEquals("Physics", aliases.courseName("BPHY101", "Engineering Physics"))
        assertEquals("Physics", aliases.courseName("bphy101", "Engineering Physics"))
        assertEquals("VTOP", aliases.courseName("BCSE1", "VTOP"))
        assertEquals("VTOP", aliases.courseName("BCSE2", "VTOP"))
        assertEquals("VTOP", emptyMap<String, String>().courseName("BCSE2", "VTOP"))
    }

    @Test
    fun sinceYouLastLookedTakesYourNames() {
        val s = snap(
            listOf(
                "+ Engineering Physics: present · 38/51",
                "− Engineering Physics Lab: absent · 10/12",
                "+ Mark: Engineering Physics · Engineering Physics Quiz · 9/10",
                "+ Mark: Chemistry · Quiz 1 · 9/10",
            ),
        ).withAliases(mapOf("BPHY101" to "Physics"))
        assertEquals(
            listOf(
                "+ Physics: present · 38/51",
                "− Engineering Physics Lab: absent · 10/12",
                "+ Mark: Physics · Engineering Physics Quiz · 9/10",
                "+ Mark: Chemistry · Quiz 1 · 9/10",
            ),
            s.changes,
        )
    }

    @Test
    fun aMessagesCourseIsFoundByCodeOrName() {
        val aliases = mapOf("BPHY102" to "Lab")
        val courses = listOf("BPHY101" to "Engineering Physics", "BPHY102" to "Engineering Physics Lab")
        assertEquals("Lab", aliases.courseIn("BPHY102 - Engineering Physics Lab - LO", courses))
        assertEquals("Lab", aliases.courseIn("Engineering Physics Lab", courses))
        assertEquals("Engineering Physics", aliases.courseIn("Engineering Physics", courses))
        assertEquals("Maths", aliases.courseIn("Maths", courses))
    }

    @Test
    fun theFacultyListKeepsVtopsName() {
        val mine = snap().withAliases(mapOf("BPHY101" to "Physics"))
        val c = Correction(physics.key, "22-Oct-2026", "A1", onDuty = false, at = 0)
        val text = correctionsMessage(listOf(c), mine, snap().coursesForAliases().toMap())
        assertTrue("BPHY101 Engineering Physics (Embedded Theory)" in text)
        assertTrue("Physics (" !in text.replace("Engineering Physics (", ""))
    }
}
