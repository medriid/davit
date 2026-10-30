package dev.medriid.davit.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTest {
    private val limit = 4L * 1024 * 1024 - 16 * 1024

    /** Tries until it fits, with a pretend encoder whose sizes stray [error] from the estimate. */
    private fun fit(firstSize: Long, error: Double): Pair<Int, List<Int>> {
        var at = 0
        val tried = mutableListOf(0)
        while (true) {
            val size = (firstSize * Fit.weight(Fit.ladder[at]) * (1 + error * if (at % 2 == 0) 1 else -1)).toLong()
            at = Fit.next(at, size, limit) ?: return at to tried
            tried += at
        }
    }

    @Test
    fun aScanThatFitsStaysSharp() {
        assertNull(Fit.next(0, 3_000_000, limit))
    }

    @Test
    fun aBigScanGetsThereInAFewTriesNotTheWholeLadder() {
        for (first in listOf(5_000_000L, 9_000_000L, 20_000_000L, 40_000_000L)) {
            val (at, tried) = fit(first, error = 0.15)
            val size = first * Fit.weight(Fit.ladder[at])
            assertTrue("$first fits", size * 1.15 <= limit || at == Fit.ladder.lastIndex)
            assertTrue("$first took ${tried.size} tries", tried.size <= 4)
        }
    }

    @Test
    fun itPicksTheSharpestStepExpectedToFit() {
        // 6 MB at the first step: two steps down (about 0.62 of the size) is the first with room.
        val next = Fit.next(0, 6_000_000, limit)!!
        assertTrue(6_000_000 * Fit.weight(Fit.ladder[next]) <= limit * 0.95)
        assertTrue(6_000_000 * Fit.weight(Fit.ladder[next - 1]) > limit * 0.95)
    }

    @Test
    fun theLastStepIsTheFloor() {
        assertEquals(Fit.ladder.lastIndex, Fit.next(0, 500_000_000, limit))
        assertNull(Fit.next(Fit.ladder.lastIndex, 500_000_000, limit))
    }
}
