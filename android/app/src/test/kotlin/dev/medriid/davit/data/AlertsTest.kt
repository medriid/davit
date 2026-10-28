package dev.medriid.davit.data

import dev.medriid.davit.vtop.Assignment
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.CourseAssignments
import dev.medriid.davit.vtop.CourseMarks
import dev.medriid.davit.vtop.Exam
import dev.medriid.davit.vtop.GradeHistory
import dev.medriid.davit.vtop.MarkItem
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlertsTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val sem = Semester("VL1", "Fall")

    private fun att(a: Int, t: Int) = AttendanceRow("BACHY105", "Applied Chemistry", "Theory", a, t, null, "VL1")
    private fun snap(
        attendance: List<AttendanceRow> = emptyList(),
        marks: List<CourseMarks> = emptyList(),
        assignments: List<CourseAssignments> = emptyList(),
        exams: List<Exam> = emptyList(),
        cgpa: Double? = null,
    ) = Snapshot(sem, listOf(sem), attendance, emptyList(), 0, emptyList(), marks, exams, cgpa?.let { GradeHistory(it, null, emptyList()) }, assignments)

    private fun mark(title: String, scored: Double?) = MarkItem(title, 10.0, 10.0, "Present", scored, scored, "")
    private fun course(vararg items: MarkItem) = CourseMarks("VL1", "BACHY105", "Applied Chemistry", "ETH", items.toList())

    @Test
    fun firstSyncIsSilent() {
        assertTrue(alertsFor(null, snap(listOf(att(1, 4))), today).isEmpty())
    }

    @Test
    fun droppingBelowSeventyFiveAlertsOnce() {
        val alerts = alertsFor(snap(listOf(att(30, 40))), snap(listOf(att(30, 41))), today)
        assertEquals(listOf("Applied Chemistry is below 75%"), alerts.map { it.title })
        assertEquals("73% (30/41). Attend the next 3 to get back.", alerts[0].text)
    }

    @Test
    fun landingOnSeventyFiveWarnsNoSkipsLeft() {
        // 30/39 could skip 1; 30/40 can skip none.
        val alerts = alertsFor(snap(listOf(att(30, 39))), snap(listOf(att(30, 40))), today)
        assertEquals(listOf("No skips left in Applied Chemistry"), alerts.map { it.title })
    }

    @Test
    fun cgpaNineIsExemptFromAttendanceAlerts() {
        val alerts = alertsFor(snap(listOf(att(30, 40)), cgpa = 9.0), snap(listOf(att(30, 41)), cgpa = 9.0), today)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun newMarksAlertButNotOnTheFirstMarksFetch() {
        val before = snap(marks = listOf(course(mark("Quiz 1", 8.0), mark("Quiz 2", null))))
        val after = snap(marks = listOf(course(mark("Quiz 1", 8.0), mark("Quiz 2", 9.5))))
        assertEquals(listOf("Quiz 2: 9.5/10"), alertsFor(before, after, today).map { it.text })
        assertTrue(alertsFor(snap(), after, today).isEmpty())
    }

    @Test
    fun deadlinesTodayAndTomorrowOnlyWhenNotSubmitted() {
        val items = listOf(
            Assignment("DA 1", 10.0, 10.0, "29-Oct-2026", null),
            Assignment("DA 2", 10.0, 10.0, "29-Oct-2026", "27-Oct-2026 09:00 PM"),
            Assignment("DA 3", 10.0, 10.0, "28-Oct-2026", null),
            Assignment("DA 4", 10.0, 10.0, "05-Nov-2026", null),
        )
        val s = snap(assignments = listOf(CourseAssignments("VL1", "BACHY105", "Applied Chemistry", "ETH", items)))
        assertEquals(listOf("Due tomorrow: DA 1", "Due today: DA 3"), alertsFor(s, s, today).map { it.title })
    }

    @Test
    fun examTomorrow() {
        val exam = Exam("VL1", "BACHY105", "Applied Chemistry", "CAT1", "29-Oct-2026", "FN", "09:30 AM - 11:00 AM", "SJT-401", "14 (R2)")
        val alerts = alertsFor(snap(), snap(exams = listOf(exam)), today)
        assertEquals("CAT1 tomorrow: Applied Chemistry", alerts.single().title)
        assertEquals("09:30 AM - 11:00 AM · SJT-401 · Seat 14 (R2)", alerts.single().text)
    }
}
