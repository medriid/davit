package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.GradeHistory
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.TimetableCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LeaveTest {
    private val sem = Semester("VL1", "Fall Semester 2026-27")

    // Mon 26 Oct to Tue 3 Nov: Sat 31 runs Wednesday's order, Wed 4 Nov is a holiday,
    // labs end Fri 30 Oct, theory Tue 3 Nov.
    private val cal = AcademicCalendar(
        "VL1", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 30),
        (26..31).map { d -> LocalDate.of(2026, 10, d) }.map { d ->
            if (d.dayOfMonth == 31) CalDay(d, "Instructional", 3, null)
            else CalDay(d, "Instructional", d.dayOfWeek.value, null)
        } + listOf(
            CalDay(LocalDate.of(2026, 11, 1), "Holiday", 0, null),
            CalDay(LocalDate.of(2026, 11, 2), "Instructional", 1, null),
            CalDay(LocalDate.of(2026, 11, 3), "Instructional", 2, null),
            CalDay(LocalDate.of(2026, 11, 4), "Holiday", 0, "Deepavali"),
        ),
    )

    // A1 meets Monday 08:00 and Wednesday 09:00; the lab pair L31+L32 is one Monday afternoon session.
    private val theory = TimetableCourse("VL1A", "BXYZ101", "Applied Science", "Embedded Theory", 3.0, "A1", "SJT-401", "F")
    private val lab = TimetableCourse("VL1L", "BXYZ101", "Applied Science", "Embedded Lab", 1.0, "L31+L32", "SJT-210", "F")

    private fun snap(theoryRow: AttendanceRow, cgpa: Double? = null) = Snapshot(
        sem, listOf(sem), listOf(theoryRow, labRow), listOf(theory, lab), 0, emptyList(),
        grades = cgpa?.let { GradeHistory(it, null, emptyList()) },
        calendar = cal,
    )

    private fun row(a: Int, t: Int) = AttendanceRow("BXYZ101", "Applied Science", "Embedded Theory", a, t, null, "VL1A")
    private val labRow = AttendanceRow("BXYZ101", "Applied Science", "Embedded Lab", 10, 10, null, "VL1L")

    private val monday = LocalDateTime.of(2026, 10, 26, 7, 0)

    @Test
    fun aSaturdayOnWednesdaysOrderMissesWednesdaysClass() {
        val i = snap(row(30, 39)).afterLeave(setOf(LocalDate.of(2026, 10, 31)), monday).first { it.row.classId == "VL1A" }
        assertEquals(1, i.missed)
        // Mon 26 and Wed 28 before it are taken as attended.
        assertEquals(2, i.between)
        assertEquals(32, i.after.attended)
        assertEquals(42, i.after.total)
    }

    @Test
    fun holidaysCostNothingAndALabSessionCountsOnce() {
        val s = snap(row(30, 40))
        val holiday = s.afterLeave(setOf(LocalDate.of(2026, 11, 4)), monday)
        assertTrue(holiday.all { it.missed == 0 })
        val mon = s.afterLeave(setOf(LocalDate.of(2026, 10, 26)), monday).first { it.row.classId == "VL1L" }
        assertEquals(1, mon.missed)
        // Labs end on the 30th, so a lab Monday after that isn't a lab day.
        assertEquals(0, s.afterLeave(setOf(LocalDate.of(2026, 11, 2)), monday).first { it.row.classId == "VL1L" }.missed)
    }

    @Test
    fun theLineIsTheExactFraction() {
        // Leave on Monday the 26th only (no classes before it): 30/39 → 30/40 is exactly 75%, still in.
        val one = snap(row(30, 39)).afterLeave(setOf(LocalDate.of(2026, 10, 26)), monday).first { it.row.classId == "VL1A" }
        assertEquals(30, one.after.attended)
        assertEquals(40, one.after.total)
        assertEquals(LeaveVerdict.Safe, one.verdict)
        // 29/39 → 29/40 is 72.5%: below, and with few classes left this semester, out of reach.
        val two = snap(row(29, 39)).afterLeave(setOf(LocalDate.of(2026, 10, 26)), monday).first { it.row.classId == "VL1A" }
        assertTrue(two.after.atRisk)
        assertEquals(LeaveVerdict.OutOfReach, two.verdict)
    }

    @Test
    fun todaysEarlierClassesAndPastDaysDontCount() {
        val afterTen = LocalDateTime.of(2026, 10, 26, 10, 0)
        val s = snap(row(30, 40))
        assertEquals(0, s.afterLeave(setOf(LocalDate.of(2026, 10, 26)), afterTen).first { it.row.classId == "VL1A" }.missed)
        assertEquals(0, s.afterLeave(setOf(LocalDate.of(2026, 10, 20)), afterTen).sumOf { it.missed })
    }

    @Test
    fun cgpaNineIsExempt() {
        val i = snap(row(10, 40), cgpa = 9.1).afterLeave(setOf(LocalDate.of(2026, 10, 26)), monday).first { it.row.classId == "VL1A" }
        assertEquals(LeaveVerdict.Exempt, i.verdict)
        assertFalse(i.verdict == LeaveVerdict.OutOfReach)
    }

    @Test
    fun remainingForStillCountsTheSameWay() {
        // Mon 26 (A1), Wed 28 (A1), Sat 31 on Wednesday's order (A1), Mon 2 Nov (A1): 4 to the end of theory.
        assertEquals(4, snap(row(30, 40)).remainingFor(row(30, 40), monday))
    }
}
