package dev.medriid.davit.data

import androidx.compose.ui.graphics.Color
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.argb
import dev.medriid.davit.design.contrast
import dev.medriid.davit.design.hex
import dev.medriid.davit.design.hsvColor
import dev.medriid.davit.design.parseHex
import dev.medriid.davit.design.toHsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own colours: a pair untouched stays the pair; anything you change still reads. */
class PaletteTest {
    @Test
    fun anUntouchedPairPrintsExactlyAsBefore() {
        for (i in InkPairs.all.indices) {
            val p = Palette(base = i)
            assertFalse(p.custom)
            for (dark in listOf(false, true)) {
                val want = InkPairs.of(i, dark)
                val got = p.inks(dark)
                assertEquals(want.paper, got.paper)
                assertEquals(want.a, got.a)
                assertEquals(want.b, got.b)
                assertEquals(want.text, got.text)
                assertEquals(want.dark, got.dark)
            }
        }
    }

    @Test
    fun nightPrintIsTonedDownAndStillReads() {
        for (i in InkPairs.all.indices) {
            val day = InkPairs.of(i, false)
            val night = InkPairs.of(i, true)
            // Calmer than the day's spot ink, so nothing glows on the charcoal stock.
            assertTrue(night.a.toHsv()[1] < day.a.toHsv()[1])
            assertTrue(night.b.contrast(night.paper) >= 3f)
            assertTrue(night.text.contrast(night.paper) >= 7f)
            assertTrue(day.text.contrast(day.paper) >= 4.5f)
            assertEquals(androidx.compose.ui.graphics.BlendMode.SrcOver, night.blend)
            for (k in Kind.entries) {
                assertTrue("${k.name} plate", night.plate(k).toHsv()[1] < k.default.toHsv()[1] || k.default.toHsv()[1] == 0f)
                assertTrue("${k.name} shows", night.plate(k).contrast(night.paper) >= 2.2f)
            }
        }
    }

    @Test
    fun textIsWorkedOutToReadOnAnyPaperYouPick() {
        val papers = listOf(0xFFFFFFFF, 0xFFFFF3B0, 0xFF9ED8FF, 0xFF7A7A7A, 0xFF3A0F2E, 0xFF000000)
        for (paper in papers) {
            for (b in listOf(0xFFFFE800, 0xFF00838A, 0xFF2A2A2A, 0xFFFF48B0)) {
                val p = Palette(paper = paper, nightPaper = paper, b = b)
                for (dark in listOf(false, true)) {
                    val i = p.inks(dark)
                    // 7:1, or the most the paper allows (mid grey tops out under 5).
                    val best = minOf(7f, maxOf(Color.Black.contrast(i.paper), Color.White.contrast(i.paper)) - 0.05f)
                    assertTrue("text on ${i.paper.hex()} with ${Color(b).hex()}: ${i.text.contrast(i.paper)}", i.text.contrast(i.paper) >= best)
                }
            }
        }
    }

    @Test
    fun aSecondaryTooFaintForTheNightIsLiftedThereButKeptByDay() {
        val navy = 0xFF14204A
        val p = Palette(b = navy)
        assertEquals(Color(navy), p.inks(false).b)
        val night = p.inks(true)
        assertTrue(night.b.contrast(night.paper) >= 3f)
        assertTrue(night.dark)
        assertEquals("Your own", night.name)
    }

    @Test
    fun aDarkDayPaperPrintsLikeNight() {
        val p = Palette(paper = 0xFF101820)
        assertTrue(p.inks(false).dark)
        // The mascot still gets day paper to sit on.
        assertFalse(InkPairs.onPaper(p.inks(true)).dark)
    }

    @Test
    fun kindsDefaultUntilYouChooseAndGoBackWhenCleared() {
        val p = Palette().withKind(Kind.Exams, Color(0xFF67B346))
        assertEquals(Color(0xFF67B346), p.kind(Kind.Exams))
        assertEquals(Kind.Classes.default, p.kind(Kind.Classes))
        val day = p.inks(false)
        // By day the shadow is black and the card takes a light pull of your colour; the colour itself stays yours.
        assertEquals(Color(0xFF67B346), day.mark(Kind.Exams))
        assertEquals(day.text, day.plate(Kind.Exams))
        assertEquals(day.text, day.plate(null))
        // A tint is paper with a wash of the kind, and plain paper when tints are off.
        assertTrue(day.fill(Kind.Exams) != day.paper)
        assertEquals(day.paper, p.copy(tinted = false).inks(false).fill(Kind.Exams))
        assertTrue(p.custom)
        assertFalse(p.withKind(Kind.Exams, null).custom)
    }

    @Test
    fun itComesBackAsItWasSaved() {
        val p = Palette(base = 2, a = 0xFF67B346, nightPaper = 0xFF1B1B2F, text = 0xFF102030, kinds = mapOf("Events" to 0xFF5EC8E5), tinted = false)
        assertEquals(p, Palette.fromJson(p.toJson()))
        assertNull(Palette.fromJson("not json"))
        assertEquals(Palette(), Palette.fromJson("{}"))
    }

    @Test
    fun hexAndHsvRoundTrip() {
        assertEquals(Color(0xFFF65058), parseHex("#f65058"))
        assertEquals(Color(0xFFFF5500), parseHex("f50"))
        assertNull(parseHex("#12345"))
        assertNull(parseHex("zzzzzz"))
        assertEquals("#00838A", Color(0xFF00838A).hex())
        assertEquals(0xFF00838A, Color(0xFF00838A).argb())
        for (c in listOf(0xFFF65058, 0xFF00838A, 0xFFFFB511, 0xFF765BA7, 0xFF2A2A2A)) {
            val x = Color(c).toHsv()
            assertEquals(Color(c).hex(), hsvColor(x[0], x[1], x[2]).hex())
        }
    }
}
