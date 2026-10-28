package dev.medriid.davit.ui

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
import dev.medriid.davit.data.Snapshot
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
        Spacer(Modifier.height(18.dp))
        if (rows.isEmpty()) {
            InkText("VTOP has no attendance for ${snapshot.semester.name} yet.")
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.sortedBy { it.percent }.forEach { row ->
                key(row.courseCode + row.classType) { CourseCard(row) }
            }
        }
    }
}

@Composable
private fun CourseCard(row: AttendanceRow) {
    val inks = LocalInks.current
    val risk = row.atRisk
    val ink by animateColorAsState(if (risk) inks.a else inks.b, label = "ink")
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

    InkCard(Modifier.fillMaxWidth(), onClick = { open = !open }, plate = ink) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(104.dp)) {
                Heading("$pct%", 34.sp, color = ink)
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
        InkText(row.hint().replaceFirstChar { it.uppercase() }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
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
            }
        }
    }
}
