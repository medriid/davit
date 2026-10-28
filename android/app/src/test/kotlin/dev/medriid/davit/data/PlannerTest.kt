package dev.medriid.davit.data

import dev.medriid.davit.vtop.GradeEntry
import dev.medriid.davit.vtop.GradeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerTest {
    private val history = GradeHistory(
        cgpa = null, creditsEarned = null,
        entries = listOf(
            GradeEntry("A", "Calculus", 4.0, "A", ""),      // 36
            GradeEntry("B", "Physics", 4.0, "S", ""),       // 40
            GradeEntry("C", "Soft skills", 2.0, "P", ""),   // pass/fail: ignored
            GradeEntry("D", "Programming", 3.0, null, ""),  // not out yet: ignored
        ),
    )

    @Test
    fun firstSemesterIsJustTheSemesterGpa() {
        assertEquals(8.5, projectCgpa(null, listOf(4.0 to 9, 4.0 to 8))!!, 1e-9)
        assertNull(projectCgpa(null, emptyList()))
    }

    @Test
    fun projectsOverTheRecordSoFar() {
        // (36 + 40 + 4*8) / 12 = 9.0
        assertEquals(9.0, projectCgpa(history, listOf(4.0 to 8))!!, 1e-9)
    }

    @Test
    fun neededAverageForNine() {
        // 9 * (8 + 4) - 76 = 32 over 4 credits = 8.0
        assertEquals(8.0, neededAverage(history, 4.0)!!, 1e-9)
        assertNull(neededAverage(history, 0.0))
    }
}
