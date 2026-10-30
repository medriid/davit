package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.TimetableCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ClassExportTest {
    private val sem = Semester("VL1", "Fall Semester 2026-27")
    private val cal = AcademicCalendar(
        "VL1", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 30),
        (26..31).map { d -> LocalDate.of(2026, 10, d) }.map { d ->
            if (d.dayOfMonth == 31) CalDay(d, "Instructional", 3, null) else CalDay(d, "Instructional", d.dayOfWeek.value, null)
        } + listOf(
            CalDay(LocalDate.of(2026, 11, 1), "Holiday", 0, null),
            CalDay(LocalDate.of(2026, 11, 2), "Holiday", 0, "Deepavali"),
            CalDay(LocalDate.of(2026, 11, 3), "Instructional", 2, null),
        ),
    )
    private val theory = TimetableCourse("VL1A", "BXYZ101", "Applied Science", "Embedded Theory", 3.0, "A1", "SJT-401, Annexe", "F")
    private val lab = TimetableCourse("VL1L", "BXYZ101", "Applied Science", "Embedded Lab", 1.0, "L31+L32", "SJT-210", "F")
    private val snap = Snapshot(
        sem, listOf(sem),
        listOf(
            AttendanceRow("BXYZ101", "Applied Science", "Embedded Theory", 30, 40, null, "VL1A"),
            AttendanceRow("BXYZ101", "Applied Science", "Embedded Lab", 10, 10, null, "VL1L"),
        ),
        listOf(theory, lab), 0, emptyList(), calendar = cal,
    )

    @Test
    fun followsDayOrdersHolidaysAndEachEnd() {
        val events = snap.classEvents(LocalDate.of(2026, 10, 26))
        // A1: Mon 26 08:00, Wed 28 09:00, Sat 31 on Wednesday's order 09:00 (Mon 2 Nov is Deepavali).
        val a1 = events.filter { it.title == "Applied Science" }
        assertEquals(listOf(26, 28, 31), a1.map { it.date.dayOfMonth })
        assertEquals(540, a1.last().start)
        assertTrue(a1.last().notes.contains("Runs Wednesday's timetable"))
        // The lab pair is one event on Monday 26; labs end on the 30th.
        assertEquals(listOf(LocalDate.of(2026, 10, 26)), events.filter { it.title.endsWith("Lab") }.map { it.date })
    }

    @Test
    fun uidsStayTheSameAndTimesAreCampusTime() {
        val a = snap.classEvents(LocalDate.of(2026, 10, 26))
        val b = snap.classEvents(LocalDate.of(2026, 10, 26))
        assertEquals(a.map { it.uid }, b.map { it.uid })
        val ics = icsOf(a, Instant.parse("2026-10-30T00:00:00Z"))
        // 08:00 in Vellore is 02:30 UTC.
        assertTrue(ics.contains("DTSTART:20261026T023000Z"))
        assertTrue(ics.contains("LOCATION:SJT-401\\, Annexe"))
        assertTrue(ics.split("\r\n").all { it.toByteArray().size <= 75 })
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun nothingWithoutACalendar() {
        assertTrue(snap.copy(calendar = null).classEvents(LocalDate.of(2026, 10, 26)).isEmpty())
    }
}
