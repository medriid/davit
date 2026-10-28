package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marks, exams and grade history against fixtures in VTOP's shape. */
class AcademicsParsersTest {
    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()

    @Test
    fun marksPairCourseRowsWithTheirTables() {
        val marks = Parsers.marks(res("marks.html"))
        assertEquals(2, marks.size)
        val chem = marks[0]
        assertEquals("VL2026270100101", chem.classId)
        assertEquals("BACHY105", chem.courseCode)
        assertEquals("Applied Chemistry", chem.courseName)
        assertEquals(3, chem.items.size)
        val cat = chem.items[0]
        assertEquals("Continuous Assessment Test - 1", cat.title)
        assertEquals(50.0, cat.max!!, 0.0)
        assertEquals(41.0, cat.scored!!, 0.0)
        assertEquals(12.3, cat.weighted!!, 1e-9)
        assertEquals("", cat.remark)
        // "-" means nothing yet, not zero.
        assertNull(chem.items[2].scored)
        assertEquals("Not submitted", chem.items[2].remark)
        assertTrue(marks[1].items.isEmpty())
    }

    @Test
    fun examsCarryTheirGroupType() {
        val exams = Parsers.exams(res("exams.html"))
        assertEquals(3, exams.size)
        assertEquals(listOf("CAT1", "CAT1", "FAT"), exams.map { it.type })
        val first = exams[0]
        assertEquals("15-Sep-2026", first.date)
        assertEquals("09:30 AM - 11:00 AM", first.time)
        assertEquals("SJT-401", first.venue)
        assertEquals("14 (R2)", first.seat)
        assertEquals("", exams[1].seat)
    }

    @Test
    fun gradeHistoryReadsVtopsCgpa() {
        val g = Parsers.gradeHistory(res("grades.html"))
        assertEquals(9.11, g.cgpa!!, 0.0)
        assertEquals(9.0, g.creditsEarned!!, 0.0)
        // The hidden detail row is skipped; the ungraded course is kept with no grade.
        assertEquals(listOf("BMAT101L", "BPHY101L", "BENG101L", "BCSE101L"), g.entries.map { it.courseCode })
        assertNull(g.entries[3].grade)
        assertEquals(listOf(CreditDomain("Foundation Core", 51.0, 7.0), CreditDomain("University Core", 12.0, 2.0)), g.curriculum)
    }

    @Test
    fun cgpaIsComputedWhenThePageHasNone() {
        val html = res("grades.html").substringBefore("<table class=\"table\">") + "</body></html>"
        // (3*9 + 4*10 + 2*8) / 9 = 83/9
        assertEquals(83.0 / 9, Parsers.gradeHistory(html).cgpa!!, 1e-9)
    }

    @Test
    fun assignmentCoursesTakeTheClassIdFromTheButtonWhenTheCellIsEmpty() {
        val courses = Parsers.assignmentCourses(res("assignments.html"))
        assertEquals(listOf("VL2026270100101", "VL2026270100107"), courses.map { it.classId })
        assertEquals("Technical English Communication", courses[1].courseName)
    }

    @Test
    fun assignmentsKnowWhatsSubmitted() {
        val items = Parsers.assignments(res("assignment_detail.html"))
        assertEquals(3, items.size)
        assertEquals("15-Oct-2026", items[0].due)
        assertEquals("12-Oct-2026 10:41 PM", items[0].updated)
        assertNull(items[1].updated)
        assertEquals(10.0, items[1].weightage!!, 0.0)
        assertEquals("-", items[2].due)
        assertNull(items[2].max)
    }

    @Test
    fun attendanceHistoryReadsStatuses() {
        val days = Parsers.attendanceDetail(res("attendance_detail.html"))
        assertEquals(4, days.size)
        assertEquals("03-Sep-2026", days[1].date)
        assertEquals(
            listOf(AttendanceDay.Status.Present, AttendanceDay.Status.Absent, AttendanceDay.Status.OnDuty, AttendanceDay.Status.Present),
            days.map { it.status },
        )
    }

    @Test
    fun attendanceRowsCarryTheirDetailLink() {
        val row = Parsers.attendance(res("attendance.html")).first()
        assertEquals(DetailKey("VL20262701", "REGNO", "VL2026270100101", "ETH"), row.detail)
    }

    @Test
    fun profileReadsNamePhotoAndHostel() {
        val p = Parsers.profile(res("profile.html"))
        assertEquals("Asha Raman", p.name)
        assertEquals("FEMALE", p.gender)
        assertTrue(p.photo!!.startsWith("data:image/jpeg"))
        assertEquals(HostelInfo("G Block", "412", "4 Bed AC", "Special Mess"), p.hostel)
    }

    @Test
    fun dayScholarsHaveNoHostel() {
        assertNull(Parsers.profile("<table><tr><td>STUDENT NAME</td><td>X</td></tr></table>").hostel)
    }

    @Test
    fun hostelRequestsSkipHiddenCells() {
        val html = res("hostel_requests.html")
        val slip = Parsers.nightSlips(html).single()
        assertEquals("Anna Auditorium", slip.venue)
        assertEquals("Club - Rehearsal", slip.purpose)
        assertEquals("09:30 PM to 11:00 PM", slip.time)
        assertEquals("Approved", slip.status)
        assertEquals(Leave("Chennai", "Family function", "Weekend", "07-Nov-2026", "09-Nov-2026", "Pending"), Parsers.leaves(html, "LeaveAppliedTable").single())
    }

    @Test
    fun facultyCardReadsLabels() {
        val f = Parsers.faculty(res("faculty.html"), "12345")!!
        assertEquals(FacultyInfo("12345", "FACULTY A", "Assistant Professor Sr. Grade 1", "Department of Chemistry", "School of Advanced Sciences (SAS)", "faculty.a@example.edu", "SJT 411-A"), f)
        assertNull(Parsers.faculty("<table><tr><td>x</td><td>y</td></tr></table>", "1"))
    }

    @Test
    fun noGradesMeansNoCgpa() {
        assertNull(Parsers.gradeHistory("<html><body></body></html>").cgpa)
    }
}
