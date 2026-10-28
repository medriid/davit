package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Meeting
import dev.medriid.davit.vtop.TimetableCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarTest {
    // Fri 23 Oct instructional, Sat 24 Oct runs Monday's order, Sun 25 off, Mon 26 Deepavali,
    // Tue 27 instructional, Wed 28–Thu 29 FAT.
    private val published = """
        {"metadata": {"firstInstructionalDay": "2026-07-06", "lastTheoryInstructionalDay": "2026-10-27", "lastLabInstructionalDay": "2026-10-27"},
         "data": {"2026-10": [
           {"date": "2026-10-23", "dayOfWeek": 5, "type": "Instructional", "dayOrder": 5, "note": null},
           {"date": "2026-10-24", "dayOfWeek": 6, "type": "Instructional", "dayOrder": 1, "note": null},
           {"date": "2026-10-25", "dayOfWeek": 7, "type": "Holiday", "dayOrder": 0, "note": null},
           {"date": "2026-10-26", "dayOfWeek": 1, "type": "Holiday", "dayOrder": 0, "note": "Deepavali"},
           {"date": "2026-10-27", "dayOfWeek": 2, "type": "Instructional", "dayOrder": 2, "note": "Last Theory & Lab Instructional Day"},
           {"date": "2026-10-28", "dayOfWeek": 3, "type": "FAT", "dayOrder": 0, "note": null},
           {"date": "2026-10-29", "dayOfWeek": 4, "type": "FAT", "dayOrder": 0, "note": null}
         ]}}
    """.trimIndent()
    private val cal = AcademicCalendar.fromPublished("VL1", published)

    private val course = TimetableCourse("VL1", "BACHY105", "Applied Chemistry", "ETH", 4.0, "A1", "SJT", "F")
    private fun slot(day: Int, start: Int) = ClassSlot(course, Meeting(day, start, start + 50, "A1"), null)
    private val week = mapOf(1 to listOf(slot(1, 480)), 2 to listOf(slot(2, 540)), 5 to listOf(slot(5, 600)))

    @Test
    fun dayOrdersHolidaysAndExams() {
        assertEquals(1, cal.timetableDay(LocalDate.of(2026, 10, 24)))
        assertNull(cal.timetableDay(LocalDate.of(2026, 10, 26)))
        assertNull(cal.timetableDay(LocalDate.of(2026, 10, 28)))
        // Outside the calendar: the date's own weekday.
        assertEquals(3, cal.timetableDay(LocalDate.of(2026, 12, 2)))
        assertEquals(listOf(Triple("FAT", LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29))), cal.examWindows())
    }

    @Test
    fun saturdayRunsMondaysClasses() {
        val plan = planFor(week, LocalDateTime.of(2026, 10, 24, 7, 0), cal)
        assertEquals(1, plan.today.size)
        assertEquals(480, plan.next!!.meeting.start)
        assertEquals(LocalDate.of(2026, 10, 24), plan.nextDate)
    }

    @Test
    fun nextSkipsTheHoliday() {
        // After Saturday's class: Sunday off, Monday is Deepavali, so next is Tuesday.
        val plan = planFor(week, LocalDateTime.of(2026, 10, 24, 12, 0), cal)
        assertEquals(3, plan.nextInDays)
        assertEquals(LocalDate.of(2026, 10, 27), plan.nextDate)
        // Without the calendar it would have been Monday.
        assertEquals(2, planFor(week, LocalDateTime.of(2026, 10, 24, 12, 0)).nextInDays)
    }

    @Test
    fun cachedFormatRoundTrips() {
        assertEquals(cal, AcademicCalendar.fromJson(cal.toJson()))
    }

    @Test
    fun semesterSkipBudget() {
        val row = AttendanceRow("BACHY105", "Applied Chemistry", "Theory", 30, 40, null, "VL1")
        // 30/40 with 8 left: attend all → 38/48; can miss k while 4(38-k) ≥ 3·48 → k ≤ 2.
        assertEquals(2, row.skipTillEnd(8))
        assertEquals("can skip 2 of the 8 left", row.hint(8))
        val low = AttendanceRow("X", "Y", "Theory", 10, 30, null, "")
        assertEquals("75% can't be reached with 4 classes left", low.hint(4))
        assertEquals(low.hint(), low.hint(null))
    }

    @Test
    fun facultyNamesMatchLoosely() {
        assertEquals("ASHA RAMAN", facultyKey("Dr. Asha  Raman"))
        assertEquals("ASHA RAMAN", facultyKey("ASHA RAMAN"))
    }
}
