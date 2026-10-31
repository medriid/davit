package dev.medriid.davit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.RisoType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** [text] as a QR code, black on white whatever the look, so any camera reads it. */
@Composable
internal fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier, description: String = "QR code") {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        }.getOrNull()
    }
    Box(
        modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(size)) {
            val m = matrix ?: return@Canvas
            val cell = this.size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * The camera, reading QR codes: the first one found goes to [onCode]. Frames are looked at
 * on the phone and dropped; nothing is saved.
 */
@Composable
internal fun QrScanner(onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ preview }, Modifier.fillMaxSize())
            val worker = remember { Executors.newSingleThreadExecutor() }
            LaunchedEffect(preview) {
                val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
                }
                val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val reader = QRCodeReader()
                var found = false
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
                    a.setAnalyzer(worker) { image ->
                        image.use {
                            if (found) return@use
                            // The brightness plane is all a QR code needs.
                            val plane = it.planes[0]
                            val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                            val text = runCatching {
                                reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
                            }.getOrNull()
                            reader.reset()
                            if (text != null) {
                                found = true
                                ContextCompat.getMainExecutor(context).execute { onCode(text) }
                            }
                        }
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
                }.onFailure { error = "Couldn't open the camera. Ask your friend to send their code as a message instead." }
            }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                    worker.shutdown()
                }
            }
            // Where to hold the code.
            Box(Modifier.align(Alignment.Center).size(240.dp).border(3.dp, Color.White, RoundedCornerShape(16.dp)))
        }
        Column(
            Modifier.fillMaxWidth().background(Color(0x99000000)).windowInsetsPadding(WindowInsets.statusBars).padding(Gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(if (granted) "Point at your friend's daVIT code" else "Scanning needs the camera", style = RisoType.title.copy(color = Color.White))
            BasicText(error ?: "Their code is in Free time with friends, on their phone.", style = RisoType.small.copy(color = Color(0xFFDDDDDD)))
            Spacer(Modifier.height(4.dp))
            if (!granted) InkButton("Allow the camera", { ask.launch(Manifest.permission.CAMERA) })
            InkButton("Close", onClose, quiet = true)
        }
    }
}
