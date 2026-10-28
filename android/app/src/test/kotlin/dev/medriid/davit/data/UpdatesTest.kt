package dev.medriid.davit.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesNumericallyNotAlphabetically() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.2"))
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updates.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updates.isNewer("0.2.0", "0.2.0-m1"))
    }
}
