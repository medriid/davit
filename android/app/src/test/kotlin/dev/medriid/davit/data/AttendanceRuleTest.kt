package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 75% rule on the exact fraction: exactly 75% is in, anything below is out, however
 * VTOP rounds it for display.
 */
class AttendanceRuleTest {
    private fun row(attended: Int, total: Int, vtop: Int? = null) =
        AttendanceRow("X", "Course", "Theory", attended, total, vtop, "")

    @Test
    fun exactlySeventyFiveIsIn() {
        val r = row(30, 40)
        assertFalse(r.atRisk)
        assertEquals(75, r.percent)
        assertEquals(0, r.canSkipNow)
        assertEquals(0, r.mustAttend)
    }

    @Test
    fun seventyFourPointFiveIsOutEvenWhenVtopSays75() {
        val r = row(149, 200, vtop = 75)
        assertTrue(r.atRisk)
        assertEquals(74, r.percent)
        // 153/204 is exactly 75%; 152/203 isn't.
        assertEquals(4, r.mustAttend)
        assertFalse(row(149 + r.mustAttend, 200 + r.mustAttend).atRisk)
        assertTrue(row(148 + r.mustAttend, 199 + r.mustAttend).atRisk)
    }

    @Test
    fun skipBudgetLandsExactlyOnTheLine() {
        val r = row(34, 40)
        assertEquals(5, r.canSkipNow)
        assertFalse(row(34, 40 + r.canSkipNow).atRisk)
        assertTrue(row(34, 41 + r.canSkipNow).atRisk)
    }

    @Test
    fun shownPercentNeverRoundsUp() {
        for (total in 1..200) for (attended in 0..total) {
            val r = row(attended, total)
            if (r.atRisk) assertTrue("$attended/$total shows ${r.percent}%", r.percent < 75)
            else assertTrue("$attended/$total shows ${r.percent}%", r.percent >= 75)
        }
    }
}
