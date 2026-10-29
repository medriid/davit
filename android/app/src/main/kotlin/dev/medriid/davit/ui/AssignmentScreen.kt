package dev.medriid.davit.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.UploadState
import dev.medriid.davit.data.Uploads
import dev.medriid.davit.data.sizeText
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
import dev.medriid.davit.vtop.Assignment
import dev.medriid.davit.vtop.CourseAssignments
import dev.medriid.davit.vtop.UserConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The id an assignment is opened by: its VTOP code, or its row when VTOP gave none. */
internal fun Assignment.routeCode(index: Int): String = code.ifEmpty { "#$index" }

/**
 * One assignment: its question paper and your file to download, and the upload. A first
 * upload is pick → confirm → send; replacing a file adds VTOP's emailed 6-digit code.
 */
@Composable
internal fun AssignmentScreen(route: Route.Assignment) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val course = state.snapshot?.assignments?.firstOrNull { it.classId == route.classId }
    val index = course?.items?.indexOfFirst { it.code == route.code }?.takeIf { it >= 0 }
        ?: route.code.removePrefix("#").toIntOrNull()
    val item = index?.let { course?.items?.getOrNull(it) }
    if (course == null || item == null) {
        RouteScreen(route, "Assignment") { InkText("This assignment isn't in the last sync any more.") }
        return
    }
    val today = LocalDate.now()
    RouteScreen(route, "Assignment", course.courseName) {
        AssignmentHeader(course, item, today)
        Spacer(Modifier.height(18.dp))
        if (item.code.isEmpty()) {
            InkText("VTOP's page gave no code for this one, so it can't be downloaded or uploaded from here. Use VTOP for it.", style = RisoType.small)
            return@RouteScreen
        }
        if (item.hasQuestion || item.hasSubmission) {
            GroupLabel("Files")
            InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
                if (item.hasQuestion) {
                    FileRow("qp:${course.classId}:${item.code}", "Question paper", "From your faculty", fetch = { c, out, p -> c.downloadQuestion(course.classId, item.code, out, p) })
                }
                if (item.hasQuestion && item.hasSubmission) Rule()
                if (item.hasSubmission) {
                    FileRow("da:${course.classId}:${item.code}", "Your submission", item.updated?.let { "Uploaded $it" }, fetch = { c, out, p -> c.downloadSubmission(course.classId, item.code, out, p) })
                }
            }
        }
        GroupLabel(if (item.hasSubmission || item.updated != null) "Replace your file" else "Upload")
        UploadPanel(course, item, state.snapshot?.semester?.id)
    }
}

@Composable
private fun AssignmentHeader(course: CourseAssignments, a: Assignment, today: LocalDate) {
    val day = a.dueDay()
    InkCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(a.title, style = RisoType.title, maxLines = 3)
                InkText("${course.courseCode} · ${course.courseType}", style = RisoType.small)
            }
            Spacer(Modifier.width(10.dp))
            AssignmentChip(a, today)
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        Spacer(Modifier.height(10.dp))
        val bits = listOfNotNull(
            day?.let { "Due " + it.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) + ChronoUnit.DAYS.between(today, it).let { d -> if (d >= 0) " · in $d day${if (d == 1L) "" else "s"}" else "" } },
            a.max?.let { "out of ${fmtNum(it)}" },
            a.weightage?.let { "${fmtNum(it)}% of the course" },
        )
        InkText(bits.joinToString(" · "), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
        InkText(if (a.updated != null) "Last uploaded ${a.updated}" else "Nothing uploaded yet", style = RisoType.small)
    }
}

@Composable
private fun UploadPanel(course: CourseAssignments, a: Assignment, semesterId: String?) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val key = "${course.classId}|${a.code}"
    val all by repo.uploads.state.collectAsState()
    val s = all[key]
    val replacing = a.updated != null || a.hasSubmission

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val p = withContext(Dispatchers.IO) { readPicked(context, uri) }
            repo.uploads.pick(key, p.name, p.mime, p.size, p.bytes)
        }
    }
    val pick = {
        haptics.tick()
        runCatching { picker.launch(Uploads.PickerTypes) }
        Unit
    }

    LaunchedEffect(s) {
        when (s) {
            is UploadState.Done -> {
                haptics.confirm()
                // The saved copy of your old file is out of date now.
                repo.files.delete("da:${course.classId}:${a.code}")
            }
            is UploadState.Failed -> haptics.reject()
            is UploadState.Otp -> if (s.error != null) haptics.reject()
            else -> {}
        }
    }

    if (!a.canUpload && s == null) {
        InkText(
            if (a.dueDay()?.isBefore(LocalDate.now()) == true) "The deadline has passed, so VTOP doesn't take uploads for this any more."
            else "VTOP isn't offering an upload for this one right now.",
            style = RisoType.small,
        )
        return
    }

    AnimatedContent(
        targetState = s,
        contentKey = { it?.javaClass },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "upload",
    ) { st ->
        Column(Modifier.fillMaxWidth()) {
            when (st) {
                null -> {
                    InkText(
                        "PDF, Word or Excel, up to 4 MB." +
                            if (replacing) " Replacing your file needs a 6-digit code VTOP emails you; you'll type it here." else "",
                        style = RisoType.small,
                    )
                    Spacer(Modifier.height(12.dp))
                    InkButton(if (replacing) "Choose a new file" else "Choose a file", pick, fillWidth = true, modifier = Modifier.fillMaxWidth())
                }
                is UploadState.Ready -> InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
                    InkText("Upload this?", label = true)
                    Spacer(Modifier.height(8.dp))
                    InkText(st.file.name, style = RisoType.title, maxLines = 3)
                    InkText("${sizeText(st.file.size.toLong())} · as \"${a.title}\"", style = RisoType.small)
                    if (replacing) {
                        Spacer(Modifier.height(8.dp))
                        InkText("This replaces the file you uploaded${a.updated?.let { " on $it" } ?: ""}. VTOP will email you a code to confirm it.", style = RisoType.small)
                    }
                    Spacer(Modifier.height(14.dp))
                    InkButton(
                        if (replacing) "Yes, replace it" else "Yes, upload",
                        {
                            haptics.heavy()
                            repo.uploads.send(key, UserConsent("upload ${a.code}"), semesterId, course.classId, a.code, replacing)
                        },
                        fillWidth = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton("Another file", pick, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                        InkButton("Cancel", { repo.uploads.clear(key) }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                    }
                }
                is UploadState.Sending -> InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
                    InkText(st.stage, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    Spacer(Modifier.height(10.dp))
                    HalftoneBar(st.fraction ?: 0.35f, height = 12.dp)
                    Spacer(Modifier.height(8.dp))
                    InkText(st.fraction?.let { "${(it * 100).toInt()}%" } ?: "Waiting for VTOP", style = RisoType.small)
                }
                is UploadState.Otp -> OtpCard(st, onSubmit = { code ->
                    haptics.heavy()
                    repo.uploads.submitOtp(key, UserConsent("upload otp ${a.code}"), course.classId, a.code, code)
                }, onCancel = {
                    haptics.tick()
                    repo.uploads.cancel(key, course.classId)
                })
                is UploadState.Done -> DoneCard(st) { repo.uploads.clear(key) }
                is UploadState.Failed -> InkCard(Modifier.fillMaxWidth(), plate = LocalInks.current.a, plateOffset = 4.dp) {
                    InkText("Not uploaded", label = true)
                    Spacer(Modifier.height(8.dp))
                    InkText(st.message)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton("Choose", pick, modifier = Modifier.weight(1f), fillWidth = true)
                        InkButton("Close", { repo.uploads.clear(key) }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun OtpCard(st: UploadState.Otp, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
        InkText("Check your email", label = true)
        Spacer(Modifier.height(8.dp))
        InkText(
            "VTOP emailed a 6-digit code to ${st.email.ifBlank { "your VIT email" }}. Enter it to replace your file with ${st.file}. Until then your old file stays.",
            style = RisoType.small,
        )
        Spacer(Modifier.height(12.dp))
        InkField(
            code, { code = it.filter(Char::isDigit).take(6) }, "6-digit code",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        st.error?.let {
            Spacer(Modifier.height(8.dp))
            InkText(it, style = RisoType.small.copy(color = LocalInks.current.a))
        }
        Spacer(Modifier.height(14.dp))
        InkButton("Confirm code", { onSubmit(code) }, enabled = code.length == 6, fillWidth = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        InkButton("Cancel, keep the old file", onCancel, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}

/** "Uploaded", stamped: it lands big and settles, like a rubber stamp. */
@Composable
private fun DoneCard(st: UploadState.Done, onClose: () -> Unit) {
    val inks = LocalInks.current
    val motion = rememberMotionEnabled()
    val stamp = remember { Animatable(if (motion) 1.6f else 1f) }
    LaunchedEffect(Unit) { if (motion) stamp.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
    InkCard(Modifier.fillMaxWidth(), plate = inks.b, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(if (st.replaced) "Replaced" else "Uploaded", label = true)
                Spacer(Modifier.height(6.dp))
                InkText("VTOP said \"Uploaded successfully\".", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                st.updated?.let { InkText("Last updated $it", style = RisoType.small) }
            }
            Heading(
                "Done", 30.sp, color = inks.a,
                modifier = Modifier.graphicsLayer {
                    scaleX = stamp.value
                    scaleY = stamp.value
                    rotationZ = -6f
                    alpha = (2f - stamp.value).coerceIn(0f, 1f)
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        InkButton("OK", onClose, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}

private class Picked(val name: String, val mime: String, val size: Long?, val bytes: ByteArray?)

/**
 * Name, type, size and bytes of a picked document. A file the system says is over VTOP's
 * limit isn't read at all; otherwise reading stops one chunk past it, so a big file is
 * caught without being loaded.
 */
private fun readPicked(context: android.content.Context, uri: Uri): Picked {
    val cr = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size: Long? = null
    runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val mime = cr.getType(uri).orEmpty()
    if ((size ?: 0) > Uploads.MAX) return Picked(name, mime, size, null)
    val bytes = runCatching {
        cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() <= Uploads.MAX) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
    return Picked(name, mime, size, bytes)
}

/** "12.3", "15", never "15.0". */
internal fun fmtNum(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ENGLISH, v).trimEnd('0').trimEnd('.')
