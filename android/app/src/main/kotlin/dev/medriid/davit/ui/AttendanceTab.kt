package dev.medriid.davit.ui

import dev.medriid.davit.design.InkButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.key
import dev.medriid.davit.data.remainingFor
import java.time.LocalDateTime
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.vtop.AttendanceDay
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.medriid.davit.data.THRESHOLD
import dev.medriid.davit.data.afterMissing
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.percent
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.CourseRemark
import dev.medriid.davit.data.remarkFor
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.printIn
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.draw.clip

/** Every course, sorted by risk. Tap a card for a what-if. */
@Composable
internal fun AttendanceTab(snapshot: Snapshot) {
    val rows = snapshot.attendance
    val risky = rows.count { it.atRisk }
    TabColumn {
        Heading("Attendance", 28.sp, Modifier.padding(top = 8.dp, bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip("Threshold 75%", solid = !snapshot.attendanceExempt)
            InkChip("$risky at risk")
            InkChip("${rows.size - risky} safe")
        }
        if (snapshot.attendanceExempt) {
            Spacer(Modifier.height(10.dp))
            InkText("Your CGPA is 9 or above, so the 75% rule doesn't apply to you.", style = RisoType.small)
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val nav = LocalNav.current
            InkButton("Plan a leave", { nav.go(Route.LeavePlanner) }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth().sharedCard(Route.LeavePlanner.sharedKey))
        }
        Spacer(Modifier.height(18.dp))
        if (rows.isEmpty()) {
            InkText("VTOP has no attendance for ${snapshot.semester.name} yet.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.sortedBy { it.percent }.forEachIndexed { index, row ->
                val faculty = snapshot.timetable.firstOrNull { it.classId.isNotEmpty() && it.classId == row.classId }?.faculty
                    ?: snapshot.timetable.firstOrNull { it.courseCode == row.courseCode && isLabType(it.courseType) == isLabType(row.classType) }?.faculty
                val remaining = remember(snapshot, row.key) { snapshot.remainingFor(row, LocalDateTime.now()) }
                key(row.key) { CourseCard(row, remaining, snapshot.history[row.key].orEmpty(), faculty.orEmpty(), snapshot.feeds.remarkFor(row), Modifier.printIn(index)) }
            }
        }
    }
}

@Composable
private fun CourseCard(row: AttendanceRow, remaining: Int?, history: List<AttendanceDay>, faculty: String, remark: CourseRemark?, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    val risk = row.atRisk
    val ink by animateColorAsState(if (risk) inks.a else inks.plate(dev.medriid.davit.design.Kind.Attendance), label = "ink")
    val pct by animateIntAsState(row.percent, label = "pct")
    var open by rememberSaveable { mutableStateOf(false) }

    // Crossing the threshold after a sync is felt as well as seen.
    val haptics = LocalHapticFeedback.current
    var lastRisk by remember { mutableStateOf(risk) }
    LaunchedEffect(risk) {
        if (risk != lastRisk) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            lastRisk = risk
        }
    }

    InkCard(modifier.fillMaxWidth(), onClick = { open = !open }, kind = dev.medriid.davit.design.Kind.Attendance, plate = ink) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(104.dp)) {
                Overprint("$pct%", 34.sp, plateA = ink, rest = DpOffset(2.dp, 1.5.dp), from = DpOffset(12.dp, (-7).dp), dot = 3.5.dp, registerKey = row.percent)
                Spacer(Modifier.height(8.dp))
                InkText("${row.attended}/${row.total}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            }
            Column(Modifier.weight(1f)) {
                InkText(row.courseName, style = RisoType.title, maxLines = 2)
                InkText(
                    "${row.courseCode} · ${if (isLabType(row.classType)) "Lab" else "Theory"}",
                    style = RisoType.small,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HalftoneBar(
            if (row.total == 0) 1f else row.attended.toFloat() / row.total,
            threshold = THRESHOLD.toFloat(),
            atRisk = risk,
        )
        Spacer(Modifier.height(10.dp))
        InkText(row.hint(remaining).replaceFirstChar { it.uppercase() }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
        if (remark != null && remark.remark.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            if (remark.serious) {
                // VTOP's verdict, reversed out of ink A: it decides more than the percentage does.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(inks.a).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    BasicText(
                        "VTOP: ${remark.remark}",
                        style = RisoType.body.copy(color = inks.paper, fontWeight = FontWeight.SemiBold),
                    )
                }
            } else {
                InkText("VTOP: ${remark.remark}", style = RisoType.small)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp)) {
                InkText("If you miss the next…", label = true)
                Spacer(Modifier.height(6.dp))
                listOf(1, 2, 3, 5).forEach { n ->
                    val after = row.afterMissing(n)
                    Row(Modifier.padding(vertical = 2.dp)) {
                        InkText("$n class${if (n > 1) "es" else ""}", modifier = Modifier.weight(1f))
                        InkText(
                            "$after%" + if (4 * row.attended < 3 * (row.total + n) && !risk) "  ✕" else "",
                            style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    InkText("History · last ${minOf(40, history.size)} classes", label = true)
                    Spacer(Modifier.height(8.dp))
                    HistoryDots(history.takeLast(40))
                    val missed = history.filter { it.status == AttendanceDay.Status.Absent }
                    Spacer(Modifier.height(8.dp))
                    InkText(
                        if (missed.isEmpty()) "No absences."
                        else "Missed: " + missed.takeLast(12).joinToString(", ") { shortDate(it.date) } + if (missed.size > 12) " and earlier" else "",
                        style = RisoType.small,
                    )
                }
                if (faculty.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    InkText("Faculty: $faculty", style = RisoType.small)
                }
            }
        }
    }
}

/** Present: filled ink B. Absent: filled ink A. On duty: an ink-B ring. Anything else: a faint dot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryDots(days: List<AttendanceDay>) {
    val inks = LocalInks.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        days.forEach { d ->
            Canvas(Modifier.size(12.dp)) {
                val r = size.minDimension / 2
                when (d.status) {
                    AttendanceDay.Status.Present -> drawCircle(inks.b, r)
                    AttendanceDay.Status.Absent -> drawCircle(inks.a, r)
                    AttendanceDay.Status.OnDuty -> drawCircle(inks.b, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    AttendanceDay.Status.Other -> drawCircle(inks.rule, r * 0.5f)
                }
            }
        }
    }
}

/** "03-Sep-2026" → "3 Sep"; anything else unchanged. */
private fun shortDate(text: String): String =
    vtopDate(text)?.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) ?: text
