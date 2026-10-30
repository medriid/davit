package dev.medriid.davit.scan

import android.content.Context
import android.net.Uri
import dev.medriid.davit.data.Uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One photo on a page: the file, and its corners (null = the whole photo). */
data class Shot(val id: Long, val file: File, val quad: Quad?, val found: Quad?)

/** A page of the PDF: one photo, or several stacked on one sheet. */
data class ScanPage(val id: Long, val shots: List<Shot>, val filter: ScanFilter, val turns: Int)

/** Where the scan is headed: an assignment (upload) or just a PDF. */
data class ScanTarget(val key: String? = null, val title: String = "", val course: String = "")

/** Where the next photo goes. */
sealed interface Into {
    data object NewPage : Into
    data class After(val page: Int) : Into
    data class OnPage(val page: Int) : Into
}

data class ScanState(
    val target: ScanTarget,
    val pages: List<ScanPage> = emptyList(),
    val name: String,
    /** Where the camera's next shots go; [Into.NewPage] appends. */
    val into: Into = Into.NewPage,
    /** The filter new pages get: the last one chosen. */
    val filter: ScanFilter = ScanFilter.Enhanced,
)

/** A finished PDF and what it took to fit. */
class ScanPdf(val bytes: ByteArray, val pages: Int, val fitted: Boolean)

/**
 * The document scanner's session: photos (in the app's cache until the PDF is made),
 * pages, crops and filters, and the export. One session at a time; closing deletes
 * the photos.
 */
class Scanner(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ScanState?>(null)
    val state: StateFlow<ScanState?> = _state

    private var nextId = System.currentTimeMillis()
    private fun id() = nextId++

    private val dir get() = File(context.cacheDir, "scan").apply { mkdirs() }

    fun open(target: ScanTarget) {
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH.mm", Locale.ENGLISH))
        val name = if (target.title.isNotBlank()) listOf(target.course, target.title).filter { it.isNotBlank() }.joinToString(" - ") else "Scan $stamp"
        _state.value = ScanState(target, name = name)
    }

    fun close() {
        _state.value = null
        scope.launch(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    }

    /** A new file for the camera to write the next photo into. */
    fun newPhotoFile(): File = File(dir, "shot-${id()}.jpg")

    fun aim(into: Into) = _state.update { it?.copy(into = into) }

    fun rename(name: String) = _state.update { it?.copy(name = name) }

    /** Adds a photo where [ScanState.into] points, then looks for the page in it (and cuts to it, with [autoCrop]). */
    fun add(file: File, autoCrop: Boolean = true) {
        val s = _state.value ?: return
        val shot = Shot(id(), file, null, null)
        val pages = s.pages.toMutableList()
        val newPage = { ScanPage(id(), listOf(shot), s.filter, 0) }
        var into = s.into
        when (val at = s.into) {
            Into.NewPage -> pages += newPage()
            is Into.After -> {
                val i = (at.page + 1).coerceIn(0, pages.size)
                pages.add(i, newPage())
                // The next photo goes after this one, so a burst stays in order.
                into = Into.After(i)
            }
            is Into.OnPage -> {
                val p = pages.getOrNull(at.page)
                if (p == null) pages += newPage() else pages[at.page] = p.copy(shots = p.shots + shot)
            }
        }
        _state.value = s.copy(pages = pages, into = into)
        scope.launch(Dispatchers.Default) {
            val found = runCatching { Render.detect(file) }.getOrNull() ?: return@launch
            updateShot(shot.id) { it.copy(quad = if (autoCrop) it.quad ?: found else it.quad, found = found) }
        }
    }

    /** Photos picked from the gallery: copied in (the originals are left alone), then added in order. */
    suspend fun import(uris: List<Uri>) {
        val files = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    val f = newPhotoFile()
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f.takeIf { it.length() > 0 }
                }.getOrNull()
            }
        }
        // Gallery photos are often cropped already (or screenshots): keep them whole; Auto is a tap away.
        files.forEach { add(it, autoCrop = false) }
    }

    private fun updateShot(shotId: Long, change: (Shot) -> Shot) = _state.update { s ->
        s?.copy(pages = s.pages.map { p -> p.copy(shots = p.shots.map { if (it.id == shotId) change(it) else it }) })
    }

    private fun updatePage(index: Int, change: (ScanPage) -> ScanPage) = _state.update { s ->
        s?.copy(pages = s.pages.mapIndexed { i, p -> if (i == index) change(p) else p })
    }

    fun setQuad(shotId: Long, quad: Quad?) = updateShot(shotId) { it.copy(quad = quad) }

    fun turn(page: Int) = updatePage(page) { it.copy(turns = (it.turns + 1) % 4) }

    fun filter(page: Int, f: ScanFilter) {
        updatePage(page) { it.copy(filter = f) }
        _state.update { it?.copy(filter = f) }
    }

    fun filterAll(f: ScanFilter) = _state.update { s -> s?.copy(filter = f, pages = s.pages.map { it.copy(filter = f) }) }

    fun move(page: Int, by: Int) = _state.update { s ->
        val to = page + by
        if (s == null || page !in s.pages.indices || to !in s.pages.indices) return@update s
        val l = s.pages.toMutableList()
        l.add(to, l.removeAt(page))
        s.copy(pages = l)
    }

    fun delete(page: Int) = _state.update { s -> s?.copy(pages = s.pages.filterIndexed { i, _ -> i != page }, into = Into.NewPage) }

    /** Takes one photo off a combined page onto its own page right after. */
    fun split(page: Int, shotId: Long) = _state.update { s ->
        val p = s?.pages?.getOrNull(page) ?: return@update s
        if (p.shots.size < 2) return@update s
        val shot = p.shots.first { it.id == shotId }
        val l = s.pages.toMutableList()
        l[page] = p.copy(shots = p.shots - shot)
        l.add(page + 1, ScanPage(id(), listOf(shot), p.filter, p.turns))
        s.copy(pages = l)
    }

    /** Puts the next page's photos on this page (two half-page scans on one sheet). */
    fun joinNext(page: Int) = _state.update { s ->
        val p = s?.pages?.getOrNull(page) ?: return@update s
        val n = s.pages.getOrNull(page + 1) ?: return@update s
        val l = s.pages.toMutableList()
        l[page] = p.copy(shots = p.shots + n.shots)
        l.removeAt(page + 1)
        s.copy(pages = l)
    }

    /**
     * The PDF. With [limit] (VTOP's 4 MB) it steps resolution and JPEG quality down until
     * it fits: sharp first, smaller only if it has to be.
     */
    suspend fun export(limit: Int? = null): ScanPdf = withContext(Dispatchers.Default) {
        val s = _state.value ?: throw IllegalStateException("Nothing scanned.")
        if (s.pages.isEmpty()) throw IllegalStateException("Add a page first.")
        val steps = listOf(2200 to 82, 1800 to 76, 1500 to 70, 1300 to 62, 1100 to 55, 900 to 50, 750 to 45)
        var last: ByteArray? = null
        for ((side, quality) in steps) {
            // A phone short of memory gets the next size down rather than a crash.
            val bytes = try {
                build(s, side, quality)
            } catch (_: OutOfMemoryError) {
                System.gc()
                continue
            }
            last = bytes
            if (limit == null || bytes.size <= limit) return@withContext ScanPdf(bytes, s.pages.size, side != steps.first().first)
        }
        ScanPdf(last ?: throw IllegalStateException("Not enough memory to make the PDF. Close other apps and try again."), s.pages.size, true)
    }

    private fun build(s: ScanState, side: Int, quality: Int): ByteArray {
        val pages = s.pages.map { p ->
            // Stacked photos share a page, so each can be smaller.
            val each = if (p.shots.size > 1) (side * 0.75f).toInt() else side
            val images = p.shots.mapNotNull { sh ->
                val b = Render.shot(sh.file, sh.quad, p.turns, p.filter, each) ?: return@mapNotNull null
                Triple(Render.jpeg(b, if (p.filter == ScanFilter.Mono) (quality - 15).coerceAtLeast(40) else quality), b.width, b.height)
                    .also { b.recycle() }
            }
            val (size, rects) = PdfWriter.layout(images.map { it.second to it.third })
            PdfWriter.Page(
                size.first, size.second,
                images.mapIndexed { i, (jpeg, w, h) -> PdfWriter.Placed(jpeg, w, h, false, rects[i][0], rects[i][1], rects[i][2], rects[i][3]) },
            )
        }
        return ByteArrayOutputStream().also { PdfWriter.write(pages, s.name, it) }.toByteArray()
    }

    companion object {
        /** A hair under VTOP's limit, for the PDF's own overhead and rounding. */
        const val UPLOAD_LIMIT = Uploads.MAX - 16 * 1024
    }
}
