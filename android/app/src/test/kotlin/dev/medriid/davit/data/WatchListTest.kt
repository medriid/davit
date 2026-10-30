package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchListTest {
    private val sem = Semester("VL1", "Fall")
    private fun row(code: String, a: Int, t: Int) = AttendanceRow(code, code, "Theory", a, t, null, code)
    private fun snap(vararg rows: AttendanceRow) = Snapshot(sem, listOf(sem), rows.toList(), emptyList(), 0, emptyList())

    @Test
    fun underTheLineFirstThenTheLowest() {
        val s = snap(row("SAFE", 36, 40), row("EDGE", 30, 40), row("LOW", 28, 40), row("NEAR", 32, 40))
        // 90% isn't near; exactly 75% (0 skips) and 80% (2 skips) are; 70% is under, so first.
        assertEquals(listOf("LOW", "EDGE", "NEAR"), s.watchList(AlertPrefs()).map { it.courseCode })
    }

    @Test
    fun yourOwnWarningLineCounts() {
        val s = snap(row("A", 34, 40), row("B", 39, 40))
        // 85% has 5 skips left: quiet at the 75% line, watched once you warn below 90%.
        assertEquals(emptyList<String>(), s.watchList(AlertPrefs()).map { it.courseCode })
        assertEquals(listOf("A"), s.watchList(AlertPrefs(warnBelow = 90)).map { it.courseCode })
    }
}
