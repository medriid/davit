package dev.medriid.davit.scan

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * A small PDF writer for scans: every image goes in as the JPEG it already is
 * (DCTDecode), so the file's size is the JPEGs' plus a few hundred bytes, and the
 * scanner can promise to fit VTOP's 4 MB.
 */
object PdfWriter {

    /** A JPEG placed on a page; position and size in points (1/72 inch), origin top left. */
    class Placed(
        val jpeg: ByteArray,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val gray: Boolean,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    class Page(val width: Float, val height: Float, val images: List<Placed>)

    /** A4 in points. */
    const val A4_WIDTH = 595.28f
    const val A4_HEIGHT = 841.89f

    fun write(pages: List<Page>, title: String, out: OutputStream) {
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun ascii(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, body: () -> Unit) {
            while (offsets.size < n) offsets += 0
            offsets[n - 1] = buf.size()
            ascii("$n 0 obj\n")
            body()
            ascii("\nendobj\n")
        }

        // 1 catalog, 2 page tree, 3 info; then per page: page, contents, its images.
        var n = 4
        val pageIds = mutableListOf<Int>()
        val plan = pages.map { p ->
            val pageId = n++
            val contentId = n++
            val imageIds = p.images.map { n++ }
            pageIds += pageId
            Triple(pageId, contentId, imageIds)
        }

        ascii("%PDF-1.4\n%âãÏÓ\n")
        obj(1) { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>") }
        obj(3) { ascii("<< /Title ${text(title)} /Producer (daVIT) /Creator (daVIT scanner) >>") }
        pages.forEachIndexed { i, page ->
            val (pageId, contentId, imageIds) = plan[i]
            val xobjects = imageIds.mapIndexed { k, id -> "/Im$k $id 0 R" }.joinToString(" ")
            obj(pageId) {
                ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(page.width)} ${num(page.height)}] " +
                        "/Resources << /XObject << $xobjects >> >> /Contents $contentId 0 R >>",
                )
            }
            // PDF's origin is bottom left: flip each image's y.
            val ops = page.images.mapIndexed { k, im ->
                "q ${num(im.width)} 0 0 ${num(im.height)} ${num(im.x)} ${num(page.height - im.y - im.height)} cm /Im$k Do Q"
            }.joinToString("\n").toByteArray(Charsets.ISO_8859_1)
            obj(contentId) {
                ascii("<< /Length ${ops.size} >>\nstream\n")
                buf.write(ops)
                ascii("\nendstream")
            }
            page.images.forEachIndexed { k, im ->
                obj(imageIds[k]) {
                    ascii(
                        "<< /Type /XObject /Subtype /Image /Width ${im.pixelWidth} /Height ${im.pixelHeight} " +
                            "/ColorSpace ${if (im.gray) "/DeviceGray" else "/DeviceRGB"} /BitsPerComponent 8 " +
                            "/Filter /DCTDecode /Length ${im.jpeg.size} >>\nstream\n",
                    )
                    buf.write(im.jpeg)
                    ascii("\nendstream")
                }
            }
        }
        val xref = buf.size()
        val count = offsets.size + 1
        ascii("xref\n0 $count\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size $count /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.writeTo(out)
    }

    /**
     * Lays [images] (pixel sizes) out on A4 portrait: one image fills the width with its own
     * page height (a scan looks like the paper it came from); several stack top to bottom,
     * each fitted into an equal share with a small gap.
     */
    fun layout(images: List<Pair<Int, Int>>): Pair<Pair<Float, Float>, List<FloatArray>> {
        if (images.size == 1) {
            val (w, h) = images[0]
            val height = (A4_WIDTH * h / w).coerceIn(A4_WIDTH * 0.3f, A4_WIDTH * 3f)
            return (A4_WIDTH to height) to listOf(floatArrayOf(0f, 0f, A4_WIDTH, height))
        }
        val margin = 18f
        val gap = 12f
        val slotH = (A4_HEIGHT - 2 * margin - gap * (images.size - 1)) / images.size
        val slotW = A4_WIDTH - 2 * margin
        val rects = images.mapIndexed { i, (w, h) ->
            val scale = minOf(slotW / w, slotH / h)
            val rw = w * scale
            val rh = h * scale
            floatArrayOf(margin + (slotW - rw) / 2, margin + i * (slotH + gap) + (slotH - rh) / 2, rw, rh)
        }
        return (A4_WIDTH to A4_HEIGHT) to rects
    }

    private fun num(v: Float) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /** A PDF text string: plain ASCII in brackets, anything else as UTF-16 hex with a BOM. */
    private fun text(s: String): String =
        if (s.all { it.code in 32..126 }) "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        else "<FEFF" + s.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) } + ">"
}
