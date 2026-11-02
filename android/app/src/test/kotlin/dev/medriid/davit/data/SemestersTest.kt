package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Test

class SemestersTest {
    private val winter = Semester("VL20262705", "Winter Semester 2026-27")
    private val fall = Semester("VL20262701", "Fall Semester 2026-27")
    private val summer = Semester("VL20252607", "Summer Semester 2025-26")
    private val all = listOf(winter, fall, summer)

    private fun row(attended: Int, total: Int) = AttendanceRow("BXYZ101", "Example", "Theory", attended, total, null, "VL1")
    private fun pick(vararg read: Pair<Semester, List<AttendanceRow>>) = currentSemester(all) { s -> read.toMap()[s].orEmpty() }

    @Test
    fun registeredForNextSemesterIsNotBeingInIt() {
        // VTOP's "-" counts parse as 0 of 0.
        assertEquals(fall, pick(winter to listOf(row(0, 0), row(0, 0)), fall to listOf(row(32, 42))))
    }

    @Test
    fun theFirstClassHeldMovesYouOn() {
        assertEquals(winter, pick(winter to listOf(row(0, 0), row(1, 1)), fall to listOf(row(32, 42))))
    }

    @Test
    fun aMissedFirstClassCountsAsHeld() {
        assertEquals(winter, pick(winter to listOf(row(0, 1)), fall to listOf(row(32, 42))))
    }

    @Test
    fun aFresherBeforeTheirFirstClass() {
        assertEquals(fall, pick(fall to listOf(row(0, 0))))
    }

    @Test
    fun registeredBothWaysBeforeAnyClassPicksTheEarlierOne() {
        assertEquals(fall, pick(winter to listOf(row(0, 0)), fall to listOf(row(0, 0))))
    }

    @Test
    fun nothingAnywhereFallsBackToTheNewest() {
        assertEquals(winter, pick())
    }

    @Test
    fun stopsReadingOnceItKnows() {
        val read = mutableListOf<Semester>()
        currentSemester(all) { s ->
            read += s
            if (s == fall) listOf(row(3, 4)) else emptyList()
        }
        assertEquals(listOf(winter, fall), read)
    }
}
