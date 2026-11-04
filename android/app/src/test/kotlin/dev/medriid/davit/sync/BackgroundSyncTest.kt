package dev.medriid.davit.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Background syncs keep to x:15 and x:45, and leave the small hours alone. */
class BackgroundSyncTest {
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute, second)

    @Test fun `the next slot in the hour`() {
        assertEquals(at(4, 14, 15), nextSyncAt(at(4, 14, 7)))
        assertEquals(at(4, 14, 45), nextSyncAt(at(4, 14, 30)))
    }

    @Test fun `a slot that has just fired moves on`() {
        assertEquals(at(4, 14, 45), nextSyncAt(at(4, 14, 15)))
        assertEquals(at(4, 15, 15), nextSyncAt(at(4, 14, 45)))
        assertEquals(at(4, 15, 15), nextSyncAt(at(4, 14, 45, 1)))
    }

    @Test fun `over midnight`() {
        assertEquals(at(5, 0, 15), nextSyncAt(at(4, 23, 50)))
    }

    @Test fun `nothing between 1 and 6 am`() {
        assertEquals(at(5, 0, 45), nextSyncAt(at(5, 0, 20)))
        assertEquals(at(5, 6, 15), nextSyncAt(at(5, 0, 45)))
        assertEquals(at(5, 6, 15), nextSyncAt(at(5, 3, 0)))
        assertEquals(at(5, 6, 15), nextSyncAt(at(5, 5, 50)))
        assertEquals(at(5, 6, 45), nextSyncAt(at(5, 6, 15)))
    }
}
