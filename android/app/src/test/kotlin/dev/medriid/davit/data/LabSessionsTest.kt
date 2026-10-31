package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceDay
import dev.medriid.davit.vtop.AttendanceDay.Status.Absent
import dev.medriid.davit.vtop.AttendanceDay.Status.OnDuty
import dev.medriid.davit.vtop.AttendanceDay.Status.Present
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A lab's two periods are one session: 28 sessions read as 28, not VTOP's 56. */
class LabSessionsTest {
    private fun row(type: String, attended: Int, total: Int) = AttendanceRow("BLAB101", "Lab Course", type, attended, total, null, "")

    private fun snap(row: AttendanceRow, days: List<AttendanceDay>) = Snapshot(
        semester = Semester("S", "Sem"), semesters = emptyList(), attendance = listOf(row), timetable = emptyList(),
        syncedAt = 0, changes = emptyList(), history = mapOf(row.key to days),
    )

    /** [present] sessions attended out of [sessions], one line each ("L31+L32"). */
    private fun oneLineEach(sessions: Int, present: Int) = (1..sessions).map { i ->
        AttendanceDay("%02d-10-2026".format(i), "L31+L32", if (i <= present) Present else Absent)
    }

    @Test
    fun aDoubledLabIsShownInSessions() {
        val r = snap(row("Embedded Lab", 50, 56), oneLineEach(28, 25)).withLabSessions().attendance.single()
        assertEquals(25, r.attended)
        assertEquals(28, r.total)
    }

    @Test
    fun oneLinePerPeriodCountsTheDayOnce() {
        val days = (1..28).flatMap { i ->
            val s = if (i <= 21) Present else if (i == 22) OnDuty else Absent
            listOf(AttendanceDay("%02d-10-2026".format(i), "L31", s), AttendanceDay("%02d-10-2026".format(i), "L32", s))
        }
        val r = snap(row("Lab Only", 44, 56), days).withLabSessions().attendance.single()
        assertEquals(22, r.attended)
        assertEquals(28, r.total)
    }

    @Test
    fun theSeventyFiveLineDoesNotMove() {
        // 42/56 is exactly 75%, and so is 21/28.
        val before = row("Embedded Lab", 42, 56)
        val after = snap(before, oneLineEach(28, 21)).withLabSessions().attendance.single()
        assertEquals(before.atRisk, after.atRisk)
        assertEquals(before.percent, after.percent)
    }

    @Test
    fun aLabVtopCountsOnceIsLeftAlone() {
        val s = snap(row("Embedded Lab", 25, 28), oneLineEach(28, 25))
        assertSame(s, s.withLabSessions())
    }

    @Test
    fun theoryIsNeverTouched() {
        val s = snap(row("Embedded Theory", 50, 56), oneLineEach(28, 25))
        assertSame(s, s.withLabSessions())
    }

    @Test
    fun aHistoryThatDisagreesKeepsVtopsNumbers() {
        // Missing a day: the halves don't match, so VTOP's figures stay.
        val s = snap(row("Embedded Lab", 50, 56), oneLineEach(27, 25))
        assertSame(s, s.withLabSessions())
    }
}
