package dev.medriid.davit.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** An open PDF. Android's renderer does one thing at a time, so everything goes through [lock]. */
private class PdfDocument(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (e: Exception) {
        fd.close()
        throw e
    }
    private val lock = Mutex()

    /** Each page's width over its height. */
    suspend fun ratios(): List<Float> = lock.withLock {
        withContext(Dispatchers.IO) {
            (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }
        }
    }

    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    suspend fun close() = lock.withLock {
        renderer.close()
        fd.close()
    }
}

/** A PDF inside the app: scroll through the pages, pinch to zoom. Saving it is one tap, and optional. */
@Composable
internal fun PdfScreen(route: Route.PdfView) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val saved by repo.files.saved.collectAsState()
    val isSaved = saved[route.key]?.exists() == true
    var note by remember { mutableStateOf<String?>(null) }

    // Null while it opens, or the reason it can't.
    val opened by produceState<Result<Pair<PdfDocument, List<Float>>>?>(null, route.path) {
        value = runCatching {
            withContext(Dispatchers.IO) { PdfDocument(File(route.path)) }.let { it to it.ratios() }
        }
    }
    val document = opened?.getOrNull()?.first
    DisposableEffect(document) {
        onDispose { document?.let { d -> repo.scope.launch { runCatching { d.close() } } } }
    }

    Column(Modifier.fillMaxSize().background(inks.paper)) {
        Column(Modifier.padding(horizontal = Gutter).padding(top = 4.dp, bottom = 8.dp)) {
            RouteHead(route.title, opened?.getOrNull()?.second?.size?.let { "$it page${if (it == 1) "" else "s"}" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.clickable(role = Role.Button, enabled = !isSaved) {
                        if (repo.files.keep(route.key) == null) note = "Couldn't save it."
                    },
                ) { InkChip(if (isSaved) "Saved" else "Save", solid = isSaved) }
                Box(
                    Modifier.clickable(role = Role.Button) {
                        val file = repo.files.file(route.key) ?: repo.files.keep(route.key)
                        if (file == null || !repo.files.open(file)) note = "No other app on this phone opens PDFs."
                    },
                ) { InkChip("Other app") }
            }
            note?.let { InkText(it, style = RisoType.small.copy(color = inks.a), modifier = Modifier.padding(top = 6.dp)) }
        }
        when (val r = opened) {
            null -> Box(Modifier.padding(horizontal = Gutter)) { Loading("Opening") }
            else -> r.fold(
                onSuccess = { (doc, ratios) -> PdfPages(doc, ratios) },
                onFailure = {
                    InkText(
                        "daVIT can't show this one, it may be locked or damaged. Other app might.",
                        style = RisoType.small,
                        modifier = Modifier.padding(horizontal = Gutter),
                    )
                },
            )
        }
    }
}

@Composable
private fun PdfPages(document: PdfDocument, ratios: List<Float>) {
    val list = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var shiftX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                // Two fingers zoom. Zoomed in, one finger moves the page; at normal size the list scrolls as usual.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val pan = event.calculatePan()
                            val room = size.width * (scale - 1f) / 2f
                            shiftX = (shiftX + pan.x).coerceIn(-room, room)
                            list.dispatchRawDelta(-pan.y / scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // Sharp enough to zoom a little, without a phone-sized bitmap per page times five.
        val width = with(LocalDensity.current) { (maxWidth.toPx() * 1.5f).toInt().coerceIn(600, 2000) }
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = shiftX
            },
            state = list,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(ratios) { index, ratio -> PdfPage(document, index, ratio, width) }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, ratio: Float, width: Int) {
    val page by produceState<Bitmap?>(null, index, width) { value = runCatching { document.render(index, width) }.getOrNull() }
    val shape = Modifier.fillMaxWidth().aspectRatio(ratio.coerceAtLeast(0.05f)).background(androidx.compose.ui.graphics.Color.White)
    page?.let { Image(it.asImageBitmap(), "Page ${index + 1}", shape) } ?: Box(shape)
}
