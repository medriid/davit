package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceDay
import dev.medriid.davit.vtop.AttendanceDay.Status.Absent
import dev.medriid.davit.vtop.AttendanceDay.Status.Present
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.SimpleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corrections, shared weeks and the timetable planner. The slot times come from the bundled
 * table: A1 is Monday 8:00 and Wednesday 9:00, D1 Monday 10:00 and Thursday 8:00, L1+L2 Monday
 * 8:00 to 9:40, L31+L32 Monday 14:00 to 15:40.
 */
class PlanningTest {
    private val row = AttendanceRow("BLAB101", "Lab Course", "Embedded Lab", 10, 12, null, "")

    private fun snap(vararg days: AttendanceDay) = Snapshot(
        semester = Semester("S", "Sem"), semesters = emptyList(), attendance = listOf(row), timetable = emptyList(),
        syncedAt = 0, changes = emptyList(), history = mapOf(row.key to days.toList()),
    )

    @Test
    fun aCorrectionWaitsThenTurnsFixed() {
        val c = Correction(row.key, "22-Oct-2026", "L19+L20", onDuty = false, at = 0)
        val before = snap(AttendanceDay("22-Oct-2026", "L19+L20", Absent))
        val after = snap(AttendanceDay("22-Oct-2026", "L19+L20", Present))
        assertEquals(CorrectionState.Waiting, c.stateIn(before))
        assertEquals(CorrectionState.Fixed, c.stateIn(after))
        assertEquals(listOf(c), fixedBetween(before, after, listOf(c)))
        // Already fixed before: nothing new to tell.
        assertEquals(emptyList<Correction>(), fixedBetween(after, after, listOf(c)))
        assertEquals(CorrectionState.Gone, c.stateIn(snap()))
    }

    @Test
    fun theFacultyMessageListsOnlyOpenOnes() {
        val open = Correction(row.key, "22-Oct-2026", "L19+L20", onDuty = true, at = 0)
        val fixed = Correction(row.key, "15-Oct-2026", "L19+L20", onDuty = false, at = 0)
        val s = snap(AttendanceDay("22-Oct-2026", "L19+L20", Absent), AttendanceDay("15-Oct-2026", "L19+L20", Present))
        val text = correctionsMessage(listOf(open, fixed), s)
        assertTrue("22-Oct-2026 · L19+L20 · on duty" in text)
        assertTrue("15-Oct-2026" !in text)
    }

    @Test
    fun aWeekCodeRoundTripsAndIsFoundInsideAMessage() {
        val classes = listOf(SharedClass("A1+TA1", "SJT"), SharedClass("L31+L32", "PRP"))
        val code = WeekCode.encode("Asha R", classes)
        assertEquals("Asha R" to classes, WeekCode.decode(code))
        assertEquals("Asha R" to classes, WeekCode.decode("Here's my week, paste it:\n\n$code"))
        assertNull(WeekCode.decode("just a message"))
    }

    @Test
    fun freeTimeIsTheGapsBetweenEveryonesClasses() {
        // Monday: you have A1 (8:00–8:50), they have D1 (10:00–10:50); both free 8:50–10:00 and after 10:50.
        val windows = freeTogether(mapOf("You" to listOf(SharedClass("A1", "SJT")), "Asha" to listOf(SharedClass("D1", "PRP"))), day = 1)
        assertEquals(listOf(8 * 60 + 50 to 10 * 60, 10 * 60 + 50 to 19 * 60 + 30), windows.map { it.start to it.end })
        assertEquals(mapOf("You" to "SJT"), windows[0].comingFrom)
        assertEquals(mapOf("You" to "SJT", "Asha" to "PRP"), windows[1].comingFrom)
    }

    @Test
    fun aLabAndATheorySlotAtTheSameTimeClash() {
        val theory = PlanPick("BXYZ201", "Data Structures", "A1+TA1", "SJT401", "ASHA", "ETH")
        val lab = PlanPick("BXYZ202", "Discrete Maths", "L1+L2", "PRP231", "RAMAN", "LO")
        val later = PlanPick("BXYZ203", "Physics", "L31+L32", "SJT210", "MEERA", "LO")
        val clashes = listOf(theory, lab, later).clashes()
        assertEquals(1, clashes.size)
        assertEquals(1 to 8 * 60, clashes[0].day to clashes[0].start)
        assertEquals(listOf(theory), lab.clashesIn(listOf(theory, later)))
    }

    @Test
    fun pickingAnotherClassOfTheSameTypeReplacesIt() {
        val a = PlanPick("BXYZ201", "Data Structures", "A1+TA1", "SJT401", "ASHA", "ETH")
        val b = a.copy(slot = "D1", faculty = "VIKRAM")
        val lab = a.copy(slot = "L31+L32", type = "ELA")
        assertEquals(listOf(lab, b), listOf(a, lab).with(b))
    }

    @Test
    fun allocationRowsBecomePicks() {
        val table = SimpleTable(listOf("Slot Detail", "Venue", "Faculty", "Course Type"), listOf(listOf("A1+TA1", "SJT401", "ASHA RAMAN", "ETH"), listOf("-", "", "", "")))
        assertEquals(listOf(PlanPick("BXYZ201", "Data Structures", "A1+TA1", "SJT401", "ASHA RAMAN", "ETH")), allocationOptions(table, "BXYZ201", "Data Structures"))
    }
}
