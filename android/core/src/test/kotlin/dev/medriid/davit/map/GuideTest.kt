package dev.medriid.davit.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GuideTest {
    // North 100 m, then east 50 m: a right turn at (0, 100).
    private val rightTurn = floatArrayOf(0f, 0f, 0f, 100f, 50f, 100f)

    @Test
    fun knowsWhereYouAreAndWhatsLeft() {
        val p = Guide.progress(rightTurn, 3f, 40f)!!
        assertEquals(0, p.segment)
        assertEquals(0f, p.x, 0.01f)
        assertEquals(40f, p.y, 0.01f)
        assertEquals(3f, p.off, 0.01f)
        assertEquals(60f + 50f, p.remaining, 0.01f)
        // Heading north.
        assertEquals(0f, p.heading, 0.01f)
    }

    @Test
    fun theNextTurnIsRightAtTheCorner() {
        val t = Guide.progress(rightTurn, 0f, 40f)!!.next!!
        assertEquals(60f, t.metres, 0.01f)
        assertEquals(90f, t.degrees, 0.5f)
        assertEquals("Turn right", t.words)
    }

    @Test
    fun leftIsNegativeAndAfterTheLastTurnThereIsNone() {
        val left = floatArrayOf(0f, 0f, 0f, 100f, -50f, 100f)
        assertEquals("Turn left", Guide.progress(left, 0f, 10f)!!.next!!.words)
        assertNull(Guide.progress(rightTurn, 30f, 100f)!!.next)
        // Past the corner, heading east.
        assertEquals((PI / 2).toFloat(), Guide.progress(rightTurn, 30f, 101f)!!.heading, 0.01f)
    }

    @Test
    fun aWobbleIsNotATurn() {
        // A 2 m jog sideways in a long straight path.
        val wobble = floatArrayOf(0f, 0f, 0f, 50f, 1.5f, 51f, 1.5f, 120f)
        assertNull(Guide.progress(wobble, 0f, 10f)!!.next)
    }

    @Test
    fun aheadStartsFromYou() {
        val p = Guide.progress(rightTurn, 0f, 40f)!!
        val rest = Guide.ahead(rightTurn, p)
        assertEquals(listOf(0f, 40f, 0f, 100f, 50f, 100f), rest.toList())
        assertTrue(Guide.turn(0f, -0.1f) < 0)
    }
}
