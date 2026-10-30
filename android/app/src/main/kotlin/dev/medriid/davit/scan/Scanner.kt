package dev.medriid.davit.scan

import android.content.Context
import android.net.Uri
import dev.medriid.davit.data.UploadTarget
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

/** Where the scan is headed: an assignment's upload, or just a PDF (until you pick one to upload to). */
data class ScanTarget(val upload: UploadTarget? = null) {
    val key: String? get() = upload?.key
    val title: String get() = upload?.title.orEmpty()
    val course: String get() = upload?.course.orEmpty()
}

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

/** A finished PDF and what it took to fit: [original] is its size before any compressing. */
class ScanPdf(val bytes: ByteArray, val pages: Int, val fitted: Boolean, val original: Int = bytes.size)

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

    /** A scan started on its own, now headed for [upload]; the name follows when it was still the default. */
    fun aimAt(upload: UploadTarget) = _state.update { s ->
        s?.copy(
            target = ScanTarget(upload),
            name = if (s.name.startsWith("Scan ")) listOf(upload.course, upload.title).filter { it.isNotBlank() }.joinToString(" - ") else s.name,
        )
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
     * The PDF. With [limit] (VTOP's 4 MB) it compresses until it fits: each page is cut,
     * straightened and filtered once, then only scaled and re-encoded, stepping down as
     * [Fit] chooses from the size each try came out at. Sharp first, smaller only if it has to be.
     */
    suspend fun export(limit: Int? = null): ScanPdf = withContext(Dispatchers.Default) {
        val s = _state.value ?: throw IllegalStateException("Nothing scanned.")
        if (s.pages.isEmpty()) throw IllegalStateException("Add a page first.")
        val masters = masters(s)
        var at = 0
        var first: Int? = null
        var done: ScanPdf? = null
        while (done == null) {
            // A phone short of memory gets the next size down rather than a crash.
            val bytes = try {
                build(s, masters, Fit.ladder[at])
            } catch (_: OutOfMemoryError) {
                System.gc()
                if (at == Fit.ladder.lastIndex) throw IllegalStateException("Not enough memory to make the PDF. Close other apps and try again.")
                at++
                continue
            }
            if (first == null) first = bytes.size
            val next = if (limit == null) null else Fit.next(at, bytes.size.toLong(), limit.toLong())
            if (next == null) done = ScanPdf(bytes, s.pages.size, at != 0, first) else at = next
        }
        done
    }

    /** A finished page image, kept as a sharp JPEG while the PDF is fitted. */
    private class Master(val jpeg: ByteArray, val w: Int, val h: Int)

    /** Every page's photos cut, straightened, turned and filtered, once, at the sharpest size. */
    private fun masters(s: ScanState): List<List<Master>> {
        val top = Fit.ladder.first().side
        return s.pages.map { p ->
            p.shots.mapNotNull { sh ->
                val b = try {
                    Render.shot(sh.file, sh.quad, p.turns, p.filter, top)
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    Render.shot(sh.file, sh.quad, p.turns, p.filter, top / 2)
                } ?: return@mapNotNull null
                Master(Render.jpeg(b, 94), b.width, b.height).also { b.recycle() }
            }
        }
    }

    private fun build(s: ScanState, masters: List<List<Master>>, step: FitStep): ByteArray {
        val pages = s.pages.mapIndexed { i, p ->
            // Stacked photos share a page, so each can be smaller.
            val each = if (p.shots.size > 1) (step.side * 0.75f).toInt() else step.side
            val quality = if (p.filter == ScanFilter.Mono) (step.quality - 15).coerceAtLeast(25) else step.quality
            val images = masters[i].mapNotNull { m ->
                val b = Render.decode(m.jpeg, each) ?: return@mapNotNull null
                Triple(Render.jpeg(b, quality), b.width, b.height).also { b.recycle() }
            }
            val (size, rects) = PdfWriter.layout(images.map { it.second to it.third })
            PdfWriter.Page(
                size.first, size.second,
                images.mapIndexed { k, (jpeg, w, h) -> PdfWriter.Placed(jpeg, w, h, false, rects[k][0], rects[k][1], rects[k][2], rects[k][3]) },
            )
        }
        return ByteArrayOutputStream().also { PdfWriter.write(pages, s.name, it) }.toByteArray()
    }

    companion object {
        /** A hair under VTOP's limit, for the PDF's own overhead and rounding. */
        const val UPLOAD_LIMIT = Uploads.MAX - 16 * 1024
    }
}
