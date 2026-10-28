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
    fun noGradesMeansNoCgpa() {
        assertNull(Parsers.gradeHistory("<html><body></body></html>").cgpa)
    }
}
