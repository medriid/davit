package dev.medriid.davit.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Page finding, the filters and the PDF writer, on synthetic images. */
class ScanTest {
    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A dark desk with a light, tilted sheet whose corners are [q] (in pixels). */
    private fun photo(w: Int, h: Int, q: List<Pair<Float, Float>>): IntArray = IntArray(w * h) { i ->
        val x = i % w + 0.5f
        val y = i / w + 0.5f
        if (inside(x, y, q)) rgb(225 - (x / w * 30).toInt()) else rgb(60 + (y / h * 20).toInt())
    }

    private fun inside(x: Float, y: Float, q: List<Pair<Float, Float>>): Boolean {
        var c = false
        var j = q.size - 1
        for (i in q.indices) {
            val (xi, yi) = q[i]
            val (xj, yj) = q[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    @Test
    fun findsATiltedSheet() {
        val w = 240
        val h = 320
        val corners = listOf(40f to 30f, 200f to 50f, 215f to 290f, 25f to 270f)
        val q = Scan.findPage(photo(w, h, corners), w, h)!!
        q.corners.zip(corners).forEach { (p, c) ->
            assertTrue("x ${p.x * w} vs ${c.first}", abs(p.x * w - c.first) < 6)
            assertTrue("y ${p.y * h} vs ${c.second}", abs(p.y * h - c.second) < 6)
        }
        assertTrue(q.convex)
    }

    @Test
    fun nothingToFindGivesNull() {
        assertNull(Scan.findPage(IntArray(100 * 100) { rgb(128) }, 100, 100))
        // A sheet filling the whole frame: nothing to cut away.
        assertNull(Scan.findPage(IntArray(100 * 100) { rgb(230) }, 100, 100))
    }

    @Test
    fun quadTurnsWithTheImage() {
        val q = Quad(Pt(0.1f, 0.2f), Pt(0.9f, 0.1f), Pt(0.8f, 0.9f), Pt(0.2f, 0.8f))
        assertEquals(q, q.rotated(4))
        val r = q.rotated(1)
        // The old bottom-left (0.2, 0.8) is the new top-left at (1 - 0.8, 0.2).
        assertEquals(0.2f, r.tl.x, 1e-6f)
        assertEquals(0.2f, r.tl.y, 1e-6f)
        assertTrue(r.convex)
        assertEquals(q.area, r.area, 1e-5f)
        assertTrue(!Quad(Pt(0f, 0f), Pt(1f, 1f), Pt(1f, 0f), Pt(0f, 1f)).convex)
    }

    /** White paper under a shadow that darkens it from 240 on the left to 120 on the right, with a black stroke. */
    private fun shadowedPage(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val light = 240 - x * 120 / w
        val ink = y in h / 2 - 2..h / 2 + 2 && x in w / 8..w * 7 / 8
        rgb(if (ink) light / 6 else light)
    }

    @Test
    fun enhanceLiftsTheShadow() {
        val w = 200
        val h = 120
        val out = Scan.enhance(shadowedPage(w, h), w, h)
        val left = Scan.luminance(out[10 * w + 10])
        val right = Scan.luminance(out[10 * w + w - 10])
        assertTrue("paper should be near white both sides: $left / $right", left > 225 && right > 225)
        assertTrue(Scan.luminance(out[(h / 2) * w + w / 2]) < 80)
    }

    @Test
    fun monoIsInkOnWhite() {
        val w = 200
        val h = 120
        val out = Scan.mono(shadowedPage(w, h), w, h)
        assertEquals(0xFFFFFFFF.toInt(), out[10 * w + w - 10])
        assertEquals(0xFF000000.toInt(), out[(h / 2) * w + w / 2])
        assertTrue(out.all { it == 0xFFFFFFFF.toInt() || it == 0xFF000000.toInt() })
    }

    @Test
    fun grayIsGray() {
        val w = 50
        val h = 50
        val out = Scan.gray(IntArray(w * h) { (0xFF shl 24) or (200 shl 16) or (120 shl 8) or 40 }, w, h)
        out.forEach { c -> assertEquals((c shr 16) and 0xFF, c and 0xFF) }
    }

    @Test
    fun otsuSplitsTwoTones() {
        val t = Scan.otsu(IntArray(1000) { if (it < 500) 40 else 210 })
        assertTrue(t in 40..209)
    }

    @Test
    fun pdfHasAValidCrossReference() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val (size, rects) = PdfWriter.layout(listOf(1000 to 1400))
        val (size2, rects2) = PdfWriter.layout(listOf(1000 to 700, 800 to 800))
        val pages = listOf(
            PdfWriter.Page(size.first, size.second, listOf(PdfWriter.Placed(jpeg, 1000, 1400, false, rects[0][0], rects[0][1], rects[0][2], rects[0][3]))),
            PdfWriter.Page(size2.first, size2.second, rects2.mapIndexed { i, r -> PdfWriter.Placed(jpeg, 1000, 700 + i * 100, i == 1, r[0], r[1], r[2], r[3]) }),
        )
        val out = ByteArrayOutputStream()
        PdfWriter.write(pages, "Lab record (week 3)", out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertEquals(2, Regex("/Type /Page ").findAll(text).count())
        assertEquals(3, Regex("/Filter /DCTDecode").findAll(text).count())
        assertTrue("/Title (Lab record \\(week 3\\))" in text)
        // Every xref entry points at its object, and startxref at the table.
        val startxref = text.substringAfterLast("startxref\n").substringBefore('\n').trim().toInt()
        assertTrue(text.startsWith("xref", startxref))
        val entries = text.substring(startxref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.substring(0, 10).toInt())) }
        // Catalog, page tree, info; page, contents, image; page, contents, two images.
        assertEquals(10, entries.size)
        // A single scan keeps its own shape: width A4, height in proportion.
        assertEquals(PdfWriter.A4_WIDTH * 1.4f, size.second, 0.01f)
        // Two images share an A4 page, one above the other, inside it.
        assertTrue(rects2[0][1] + rects2[0][3] <= rects2[1][1])
        assertTrue(rects2.all { it[0] >= 0 && it[1] + it[3] <= PdfWriter.A4_HEIGHT })
    }

    @Test
    fun nonAsciiTitlesGoAsUnicode() {
        val out = ByteArrayOutputStream()
        PdfWriter.write(emptyList(), "Gödel — notes", out)
        assertTrue("<FEFF0047" in String(out.toByteArray(), Charsets.ISO_8859_1))
    }
}
