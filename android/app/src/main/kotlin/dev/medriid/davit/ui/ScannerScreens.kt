package dev.medriid.davit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import dev.medriid.davit.data.UploadState
import dev.medriid.davit.data.UploadTarget
import dev.medriid.davit.data.Uploads
import dev.medriid.davit.data.sizeText
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LoadingPlate
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
import dev.medriid.davit.scan.Into
import dev.medriid.davit.scan.Pt
import dev.medriid.davit.scan.Quad
import dev.medriid.davit.scan.Render
import dev.medriid.davit.scan.ScanFilter
import dev.medriid.davit.scan.ScanPage
import dev.medriid.davit.scan.ScanState
import dev.medriid.davit.scan.Scanner
import dev.medriid.davit.scan.Shot
import dev.medriid.davit.vtop.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The document scanner, over everything while it's open: camera (a burst of pages, or
 * photos from the gallery) → the PDF as it will come out (crop, turn, filters, insert
 * pages, put photos together on one page, rename) → upload it to the assignment, save
 * it to the phone, or share it.
 */
@Composable
internal fun ScannerHost() {
    val repo = LocalRepo.current
    val state by repo.scanner.state.collectAsState()
    val s = state ?: return
    var screen by rememberSaveable { mutableStateOf("camera") }
    var crop by rememberSaveable { mutableStateOf<Long?>(null) }
    var askClose by remember { mutableStateOf(false) }

    BackHandler {
        when {
            crop != null -> crop = null
            screen == "camera" && s.pages.isNotEmpty() -> screen = "editor"
            s.pages.isEmpty() -> repo.scanner.close()
            else -> askClose = true
        }
    }
    val inks = LocalInks.current
    Box(Modifier.fillMaxSize().background(if (screen == "camera" && crop == null) Color(0xFF111214) else inks.paper)) {
        AnimatedContent(if (crop != null) "crop" else screen, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "scanner") { at ->
            when (at) {
                "camera" -> CameraScreen(
                    s,
                    onDone = { screen = "editor" },
                    onClose = { if (s.pages.isEmpty()) repo.scanner.close() else screen = "editor" },
                )
                "crop" -> {
                    val shot = s.pages.flatMap { it.shots }.firstOrNull { it.id == crop }
                    if (shot == null) {
                        LaunchedEffect(Unit) { crop = null }
                    } else {
                        CropScreen(shot, onDone = { q -> repo.scanner.setQuad(shot.id, q); crop = null }, onCancel = { crop = null })
                    }
                }
                else -> EditorScreen(
                    s,
                    onCamera = { into ->
                        repo.scanner.aim(into)
                        screen = "camera"
                    },
                    onCrop = { crop = it },
                    onClose = { if (s.pages.isEmpty()) repo.scanner.close() else askClose = true },
                )
            }
        }
        if (askClose) {
            Box(Modifier.fillMaxSize().background(inks.text.copy(alpha = 0.4f)).clickable(role = Role.Button) { askClose = false }, contentAlignment = Alignment.Center) {
                InkCard(Modifier.padding(Gutter).fillMaxWidth()) {
                    InkText("Discard this scan?", style = RisoType.title)
                    Spacer(Modifier.height(6.dp))
                    InkText("${s.pages.size} page${if (s.pages.size == 1) "" else "s"} not saved anywhere yet.", style = RisoType.small)
                    Spacer(Modifier.height(14.dp))
                    InkButton("Discard", { askClose = false; repo.scanner.close() }, fillWidth = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    InkButton("Keep editing", { askClose = false }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---- camera ------------------------------------------------------------------------------

@Composable
private fun CameraScreen(s: ScanState, onDone: () -> Unit, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val motion = rememberMotionEnabled()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            repo.scanner.import(uris)
            haptics.confirm()
            if (s.into is Into.OnPage) onDone()
        }
    }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val blink = remember { Animatable(0f) }
    val inks = LocalInks.current

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
            AndroidView({ preview }, Modifier.fillMaxSize())
            LaunchedEffect(preview) {
                val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
                }
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val c = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(92)
                    .setFlashMode(flash)
                    .build()
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, c)
                    capture = c
                }.onFailure { error = "Couldn't open the camera. You can still add photos from the gallery." }
            }
            DisposableEffect(Unit) {
                onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("The scanner needs the camera.", style = RisoType.title.copy(color = Color.White))
                Spacer(Modifier.height(8.dp))
                BasicText("Photos stay on this phone until you save, share or upload the PDF. Or pick photos from your gallery below.", style = RisoType.small.copy(color = Color(0xFFCFCFCF)))
                Spacer(Modifier.height(16.dp))
                InkButton("Allow the camera", { ask.launch(Manifest.permission.CAMERA) })
            }
        }
        // The shutter's flash.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = blink.value }.background(Color.White))

        // Top: close, where photos go, flash.
        Row(
            Modifier.fillMaxWidth().background(Color(0x88000000)).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkChip("Close", onClose)
            Spacer(Modifier.weight(1f))
            BasicText(
                when (val at = s.into) {
                    Into.NewPage -> "${s.pages.size} page${if (s.pages.size == 1) "" else "s"}"
                    is Into.After -> "Inserting after page ${at.page + 1}"
                    is Into.OnPage -> "Adding to page ${at.page + 1}"
                },
                style = RisoType.label.copy(color = Color.White),
            )
            Spacer(Modifier.weight(1f))
            DarkChip(
                when (flash) {
                    ImageCapture.FLASH_MODE_ON -> "Flash on"
                    ImageCapture.FLASH_MODE_AUTO -> "Flash auto"
                    else -> "Flash off"
                },
            ) {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                capture?.flashMode = flash
                haptics.tick()
            }
        }

        // Bottom: gallery, shutter, done.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(top = 12.dp, bottom = 18.dp),
        ) {
            error?.let {
                BasicText(it, style = RisoType.small.copy(color = Color(0xFFFFB4B4)), modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
            }
            BasicText(
                "Hold the page flat and fill the frame. Corners are found for you; adjust them after.",
                style = RisoType.small.copy(color = Color(0xFFBDBDBD)),
                modifier = Modifier.padding(horizontal = Gutter).padding(bottom = 14.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                DarkChip("Gallery") {
                    runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(78.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(if (capture != null && !busy) inks.a else Color(0xFF666666))
                        .clickable(enabled = capture != null && !busy, role = Role.Button) {
                            val c = capture ?: return@clickable
                            busy = true
                            haptics.heavy()
                            val file = repo.scanner.newPhotoFile()
                            if (motion) scope.launch {
                                blink.snapTo(0.85f)
                                blink.animateTo(0f, tween(260))
                            }
                            c.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        error = null
                                        val into = repo.scanner.state.value?.into
                                        repo.scanner.add(file)
                                        // One photo onto an existing page: straight back to see it.
                                        if (into is Into.OnPage) onDone()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        haptics.reject()
                                        error = "That photo didn't save. Try again."
                                    }
                                },
                            )
                        }
                        .semantics { contentDescription = "Take photo" },
                )
                Spacer(Modifier.weight(1f))
                LastPage(s, onDone)
            }
        }
    }
}

/** The last page's thumbnail with the count; opens the PDF. */
@Composable
private fun LastPage(s: ScanState, onDone: () -> Unit) {
    val last = s.pages.lastOrNull()
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCorner)
            .border(2.dp, Color.White, RoundedCorner)
            .clickable(enabled = s.pages.isNotEmpty(), role = Role.Button, onClick = onDone)
            .semantics { contentDescription = "Done, ${s.pages.size} pages" },
        contentAlignment = Alignment.Center,
    ) {
        if (last != null) {
            val thumb = rememberPage(last, 240)
            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.align(Alignment.BottomEnd).background(LocalInks.current.a).padding(horizontal = 6.dp, vertical = 2.dp)) {
                BasicText("${s.pages.size}", style = RisoType.label.copy(color = Color.White))
            }
        } else {
            BasicText("Done", style = RisoType.label.copy(color = Color(0xFF888888)))
        }
    }
}

private val RoundedCorner = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

@Composable
private fun DarkChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCorner)
            .background(Color(0x99000000))
            .border(1.5.dp, Color(0x66FFFFFF), RoundedCorner)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { BasicText(text.uppercase(), style = RisoType.label.copy(color = Color.White)) }
}

// ---- rendering cache -----------------------------------------------------------------------

/** Rendered pages, keyed by everything that changes how they look; a few dozen at most. */
private val pageCache = object : android.util.LruCache<String, ImageBitmap>(40) {}

private fun pageKey(p: ScanPage, side: Int) = buildString {
    append(side).append('|').append(p.filter).append('|').append(p.turns)
    p.shots.forEach { append('|').append(it.id).append(':').append(it.quad) }
}

/**
 * [p] as it will look in the PDF (crop, turn, filter; stacked photos side by side for a
 * quick look), rendered off the main thread and kept for scrolling back.
 */
@Composable
private fun rememberPage(p: ScanPage, side: Int): ImageBitmap? {
    val key = pageKey(p, side)
    val cached = pageCache.get(key)
    val img by produceState(cached, key) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                val parts = p.shots.mapNotNull { Render.shot(it.file, it.quad, p.turns, p.filter, side) }
                when (parts.size) {
                    0 -> null
                    1 -> parts[0]
                    else -> stack(parts)
                }?.asImageBitmap()?.also { pageCache.put(key, it) }
            }
        }
    }
    return img ?: cached
}

/** Photos one above the other on white, as a combined page will print. */
private fun stack(parts: List<android.graphics.Bitmap>): android.graphics.Bitmap {
    val w = parts.maxOf { it.width }
    val gap = w / 30
    val h = parts.sumOf { it.height * w / it.width } + gap * (parts.size + 1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.WHITE)
    var y = gap
    parts.forEach { b ->
        val bh = b.height * w / b.width
        c.drawBitmap(b, null, android.graphics.Rect(0, y, w, y + bh), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        y += bh + gap
    }
    return out
}

// ---- editor --------------------------------------------------------------------------------

@Composable
private fun EditorScreen(s: ScanState, onCamera: (Into) -> Unit, onCrop: (Long) -> Unit, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch { repo.scanner.import(uris) }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false) }
            if (ok) haptics.confirm() else haptics.reject()
            note = if (ok) "Saved (${sizeText(bytes.size.toLong())})." else "Couldn't save it there."
        }
    }
    val fileName = s.name.trim().ifEmpty { "Scan" }.replace(Regex("""[\\/:*?"<>|]"""), "-") + ".pdf"

    fun export(limit: Int?, then: suspend (dev.medriid.davit.scan.ScanPdf) -> Unit) {
        if (busy != null) return
        busy = if (limit != null) "Making the PDF, compressed to fit under 4 MB" else "Making the PDF"
        note = null
        scope.launch {
            runCatching { repo.scanner.export(limit) }
                .onSuccess { pdf ->
                    busy = null
                    if (limit != null && pdf.bytes.size > limit) {
                        haptics.reject()
                        note = "Compressed as far as it goes this is still ${sizeText(pdf.bytes.size.toLong())}, over VTOP's 4 MB. Drop a few pages and try again."
                    } else {
                        then(pdf)
                    }
                }
                .onFailure {
                    busy = null
                    haptics.reject()
                    note = it.message ?: "Couldn't make the PDF."
                }
        }
    }

    // Uploading from here: the PDF goes to the assignment's confirm step, shown in place of the buttons.
    val uploads by repo.uploads.state.collectAsState()
    var uploading by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    fun upload(t: UploadTarget) {
        haptics.heavy()
        export(Scanner.UPLOAD_LIMIT) { pdf ->
            val how = if (pdf.fitted) "Compressed from ${sizeText(pdf.original.toLong())} to fit VTOP's 4 MB." else null
            repo.uploads.pick(t.key, fileName, "application/pdf", pdf.bytes.size.toLong(), pdf.bytes, how)
            uploading = true
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Gutter, end = Gutter, bottom = 20.dp)) {
            item(key = "head") {
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackButton(onClose)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Heading("Scan to PDF", 24.sp)
                            Spacer(Modifier.height(6.dp))
                            InkText(
                                "${s.pages.size} page${if (s.pages.size == 1) "" else "s"}" + if (s.target.key != null) " · for ${s.target.title}" else "",
                                style = RisoType.small, maxLines = 2,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    InkField(s.name, { repo.scanner.rename(it.take(80)) }, "File name (.pdf)", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    InkText("Filter, every page", label = true)
                    Spacer(Modifier.height(6.dp))
                    val all = s.pages.map { it.filter }.distinct().singleOrNull()
                    ChoiceRow(ScanFilter.entries.map { it.label }, ScanFilter.entries.indexOf(all)) {
                        repo.scanner.filterAll(ScanFilter.entries[it])
                        haptics.tick()
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (s.pages.isEmpty()) {
                item(key = "empty") {
                    InkText("No pages yet. Take some photos or add them from your gallery.", modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            itemsIndexed(s.pages, key = { _, p -> p.id }) { i, p ->
                PageCard(i, p, s.pages.size, onCamera, onCrop, Modifier.printIn(i.coerceAtMost(6)))
            }
            item(key = "more") {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkButton("+ Camera", { onCamera(Into.NewPage) }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                    InkButton("+ Gallery", {
                        repo.scanner.aim(Into.NewPage)
                        runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                }
            }
        }
        // The actions stay put under the pages.
        Column(
            Modifier
                .fillMaxWidth()
                .background(inks.paper)
                .border(2.dp, inks.text, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Gutter, vertical = 14.dp),
        ) {
            when {
                busy != null -> {
                    InkText(busy!!, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    LoadingPlate(lines = 1)
                }
                note != null -> InkText(note!!, style = RisoType.small)
            }
            if (busy != null || note != null) Spacer(Modifier.height(10.dp))
            val target = s.target.upload
            val step = target?.let { uploads[it.key] }
            if (uploading && target != null && step != null) {
                // The confirm, progress, emailed code and done, right here.
                LaunchedEffect(step is UploadState.Done) {
                    if (step is UploadState.Done) {
                        haptics.confirm()
                        repo.files.delete("da:${target.classId}:${target.code}")
                    }
                }
                UploadStep(target, step, onClose = {
                    uploading = false
                    if (step is UploadState.Done) repo.scanner.close()
                })
                return@Column
            }
            if (choosing) {
                AssignmentChoice(onPick = { t ->
                    choosing = false
                    repo.scanner.aimAt(t)
                    upload(t)
                }, onCancel = { choosing = false })
                return@Column
            }
            InkButton(
                if (target != null) "Upload to \"${target.title}\"" else "Upload to an assignment",
                { if (target != null) upload(target) else choosing = true },
                enabled = s.pages.isNotEmpty() && busy == null, fillWidth = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Save", {
                    export(null) { pdf ->
                        pending = pdf.bytes
                        runCatching { saveAs.launch(fileName) }
                    }
                }, enabled = s.pages.isNotEmpty() && busy == null, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                InkButton("Share", {
                    export(null) { pdf ->
                        val bytes = pdf.bytes
                        val f = repo.files.fetch("scan:${System.currentTimeMillis()}") { tmp, _ ->
                            withContext(Dispatchers.IO) { tmp.writeBytes(bytes) }
                            Download(fileName, "application/pdf", bytes.size.toLong())
                        }
                        repo.files.share(f)
                        note = "Kept in Downloads too (${sizeText(bytes.size.toLong())})."
                    }
                }, enabled = s.pages.isNotEmpty() && busy == null, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
            }
        }
    }
}

/** Which assignment a scan started on its own goes to: the ones VTOP takes uploads for, soonest due first. */
@Composable
private fun AssignmentChoice(onPick: (UploadTarget) -> Unit, onCancel: () -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val snapshot = state.snapshot
    val open = snapshot?.assignments.orEmpty().flatMap { c ->
        c.items.filter { it.canUpload && it.code.isNotEmpty() }.map { a ->
            UploadTarget(c.classId, a.code, a.title, c.courseCode, snapshot?.semester?.id, a.updated != null || a.hasSubmission, a.updated) to
                (c.courseName to a.dueDay())
        }
    }.sortedWith(compareBy(nullsLast()) { it.second.second })
    InkText("Upload to", label = true)
    Spacer(Modifier.height(6.dp))
    if (open.isEmpty()) {
        InkText("No assignment is taking uploads right now. Save or share the PDF instead.", style = RisoType.small)
    } else {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            open.forEach { (t, info) ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(t) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        InkText(t.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                        InkText(
                            listOfNotNull(info.first, info.second?.let { "due " + it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)) })
                                .joinToString(" · "),
                            style = RisoType.small, maxLines = 1,
                        )
                    }
                    if (t.replacing) InkChip("Replaces")
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    InkButton("Cancel", onCancel, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PageCard(i: Int, p: ScanPage, count: Int, onCamera: (Into) -> Unit, onCrop: (Long) -> Unit, modifier: Modifier) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val haptics = rememberHaptics()
    var sure by remember(p.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText("Page ${i + 1}", label = true, modifier = Modifier.weight(1f))
            if (p.shots.size > 1) InkText("${p.shots.size} photos on this page", style = RisoType.small)
        }
        Spacer(Modifier.height(6.dp))
        val img = rememberPage(p, 1000)
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (img != null) Modifier.aspectRatio(img.width.toFloat() / img.height, matchHeightConstraintsFirst = false) else Modifier.height(240.dp))
                .border(2.dp, inks.text, RoundedCorner)
                .clip(RoundedCorner)
                .background(Color.White)
                .clickable(role = Role.Button) { p.shots.firstOrNull()?.let { onCrop(it.id) } },
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) Image(img, "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else LoadingPlate(Modifier.padding(24.dp), lines = 3)
        }
        Spacer(Modifier.height(8.dp))
        // Every page action, in one sideways row.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.shots.forEachIndexed { k, sh ->
                Action(if (p.shots.size > 1) "Crop ${k + 1}" else "Crop", solid = true) { onCrop(sh.id) }
            }
            Action("Turn") { repo.scanner.turn(i); haptics.tick() }
            Action(p.filter.label) {
                repo.scanner.filter(i, ScanFilter.entries[(p.filter.ordinal + 1) % ScanFilter.entries.size])
                haptics.tick()
            }
            Action("+ Photo on page") { onCamera(Into.OnPage(i)) }
            Action("+ Page after") { onCamera(Into.After(i)) }
            if (i < count - 1) Action("Join next") { repo.scanner.joinNext(i); haptics.tick() }
            if (p.shots.size > 1) p.shots.drop(1).forEachIndexed { k, sh -> Action("Split ${k + 2} off") { repo.scanner.split(i, sh.id) } }
            if (i > 0) Action("Up") { repo.scanner.move(i, -1); haptics.tick() }
            if (i < count - 1) Action("Down") { repo.scanner.move(i, 1); haptics.tick() }
            Action(if (sure) "Tap to delete" else "Delete", solid = sure) {
                if (sure) {
                    repo.scanner.delete(i)
                    haptics.confirm()
                } else {
                    sure = true
                }
            }
        }
    }
}

@Composable
private fun Action(text: String, solid: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)) { InkChip(text, solid = solid) }
}

// ---- crop ------------------------------------------------------------------------------------

/**
 * Drag the four corners onto the page's corners. A magnifier shows what's under your
 * finger. Auto puts back what the scanner found; Whole photo keeps everything.
 */
@Composable
private fun CropScreen(shot: Shot, onDone: (Quad?) -> Unit, onCancel: () -> Unit) {
    val inks = LocalInks.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val photo by produceState<ImageBitmap?>(null, shot.file) {
        value = withContext(Dispatchers.IO) { Render.load(shot.file, 1600)?.asImageBitmap() }
    }
    // A photo kept whole opens with the frame round all of it; Auto offers what was found.
    var quad by remember(shot.id) { mutableStateOf(shot.quad ?: Quad.Full) }
    var dragging by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        Row(Modifier.padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onCancel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Heading("Crop", 24.sp)
                InkText("Drag the corners onto the page's corners.", style = RisoType.small)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val img = photo
            if (img == null) {
                LoadingPlate(Modifier.padding(24.dp))
                return@BoxWithConstraints
            }
            // Where the photo sits inside the box (fitted, centred).
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val k = min(boxW / img.width, boxH / img.height)
            val area = Rect(Offset((boxW - img.width * k) / 2, (boxH - img.height * k) / 2), Size(img.width * k, img.height * k))
            fun toScreen(p: Pt) = Offset(area.left + p.x * area.width, area.top + p.y * area.height)
            fun toPhoto(o: Offset) = Pt(((o.x - area.left) / area.width).coerceIn(0f, 1f), ((o.y - area.top) / area.height).coerceIn(0f, 1f))
            val reach = with(density) { 56.dp.toPx() }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(area) {
                        detectDragGestures(
                            onDragStart = { at ->
                                val nearest = quad.corners.map(::toScreen).withIndex().minByOrNull { (_, c) -> hypot(c.x - at.x, c.y - at.y) }
                                dragging = if (nearest != null && hypot(nearest.value.x - at.x, nearest.value.y - at.y) < reach) nearest.index else -1
                                finger = at
                                if (dragging >= 0) haptics.tick()
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, drag ->
                            if (dragging < 0) return@detectDragGestures
                            change.consume()
                            finger += drag
                            val moved = quad.with(dragging, toPhoto(finger))
                            // Never let the page fold over itself.
                            if (moved.convex) quad = moved
                        }
                    },
            ) {
                drawImage(img, dstOffset = IntOffset(area.left.toInt(), area.top.toInt()), dstSize = IntSize(area.width.toInt(), area.height.toInt()))
                val pts = quad.corners.map(::toScreen)
                val outline = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                // Dim everything outside the page.
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(area)
                    addPath(outline)
                }
                drawPath(outside, Color.Black.copy(alpha = 0.45f))
                drawPath(outline, inks.a, style = Stroke(3.dp.toPx()))
                pts.forEachIndexed { i, c ->
                    drawCircle(Color.White, 15.dp.toPx(), c)
                    drawCircle(if (i == dragging) inks.a else inks.b, 15.dp.toPx(), c, style = Stroke(4.dp.toPx()))
                }
                // The magnifier: what's under the corner, three times bigger, above the finger.
                if (dragging >= 0) {
                    val c = pts[dragging]
                    val r = 52.dp.toPx()
                    val centre = Offset(c.x.coerceIn(r, size.width - r), (c.y - r * 2.1f).let { if (it < r) c.y + r * 2.1f else it })
                    val zoom = 3f
                    val srcHalf = r / zoom / k
                    val sx = ((c.x - area.left) / k - srcHalf).toInt().coerceIn(0, max(0, img.width - (srcHalf * 2).toInt()))
                    val sy = ((c.y - area.top) / k - srcHalf).toInt().coerceIn(0, max(0, img.height - (srcHalf * 2).toInt()))
                    val lens = Path().apply { addOval(Rect(centre, r)) }
                    clipPath(lens) {
                        drawRect(Color.White, Offset(centre.x - r, centre.y - r), Size(r * 2, r * 2))
                        drawImage(
                            img,
                            srcOffset = IntOffset(sx, sy),
                            srcSize = IntSize((srcHalf * 2).toInt().coerceAtLeast(1), (srcHalf * 2).toInt().coerceAtLeast(1)),
                            dstOffset = IntOffset((centre.x - r).toInt(), (centre.y - r).toInt()),
                            dstSize = IntSize((r * 2).toInt(), (r * 2).toInt()),
                        )
                    }
                    drawCircle(inks.text, r, centre, style = Stroke(3.dp.toPx()))
                    drawLine(inks.a, centre - Offset(10.dp.toPx(), 0f), centre + Offset(10.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(inks.a, centre - Offset(0f, 10.dp.toPx()), centre + Offset(0f, 10.dp.toPx()), 2.dp.toPx())
                }
            }
        }
        Row(Modifier.padding(horizontal = Gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shot.found != null) Action("Auto") { quad = shot.found; haptics.tick() }
            Action("Whole photo") { quad = Quad.Full; haptics.tick() }
            Action("Reset") { quad = shot.quad ?: Quad.Full }
        }
        InkButton("Done", {
            haptics.confirm()
            onDone(if (quad == Quad.Full) null else quad)
        }, fillWidth = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 12.dp))
    }
}
