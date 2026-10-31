package dev.medriid.davit.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.Correction
import dev.medriid.davit.data.CorrectionState
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.correctionsMessage
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.key
import dev.medriid.davit.data.stateIn
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.vtop.AttendanceDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Days VTOP has you absent for that you attended or were on duty for: mark them from your
 * absences, send the open ones to your faculty, and see them turn fixed as VTOP updates.
 */
@Composable
internal fun CorrectionsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    RouteScreen(
        route,
        "Attendance corrections",
        "Absent on VTOP but you were there? Mark it, send the list, and hear when it's fixed.",
    ) {
        val s = state.snapshot
        if (s == null) {
            InkText("Your attendance shows here after the first sync.")
            return@RouteScreen
        }
        val flagged = state.corrections
        val byState = flagged.groupBy { it.stateIn(s) }
        val waiting = byState[CorrectionState.Waiting].orEmpty()
        var n = 0

        if (waiting.isNotEmpty()) {
            GroupLabel("Waiting on VTOP · ${waiting.size}")
            waiting.groupBy { it.course }.forEach { (course, days) ->
                InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).printIn(n++), kind = Kind.Attendance, plateOffset = 3.dp) {
                    InkText(courseLabel(s, course), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    days.sortedByDescending { vtopDate(it.date) }.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InkText("${dayLabel(c.date)} · ${c.slot} · ${if (c.onDuty) "on duty" else "was there"}", style = RisoType.small, modifier = Modifier.weight(1f))
                            Box(Modifier.clickable(role = Role.Button) { repo.toggleCorrection(c.course, c.date, c.slot, c.onDuty) }) { InkChip("Unmark") }
                        }
                    }
                }
            }
            InkButton("Send the list to your faculty", { shareText(context, "Attendance corrections", correctionsMessage(flagged, s)) }, fillWidth = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            InkText("Opens your share menu with the list written out: course, date and slot. Pick email or WhatsApp.", style = RisoType.small)
            Spacer(Modifier.height(18.dp))
        }

        val done = byState[CorrectionState.Fixed].orEmpty() + byState[CorrectionState.Gone].orEmpty()
        if (done.isNotEmpty()) {
            GroupLabel("Settled")
            done.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(courseLabel(s, c.course), style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                        InkText(
                            "${dayLabel(c.date)} · " + if (c.stateIn(s) == CorrectionState.Fixed) "VTOP fixed it" else "VTOP no longer lists it",
                            style = RisoType.small,
                        )
                    }
                    Box(Modifier.clickable(role = Role.Button) { repo.toggleCorrection(c.course, c.date, c.slot, c.onDuty) }) { InkChip("Clear") }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        GroupLabel("Your absences")
        val rows = s.attendance.filter { r -> s.history[r.key].orEmpty().any { it.status == AttendanceDay.Status.Absent } }
        if (rows.isEmpty()) {
            InkText(if (s.history.isEmpty()) "Day-by-day attendance shows here after the next sync." else "VTOP has you present for every class. Nothing to correct.")
            return@RouteScreen
        }
        InkText("Mark the ones you were actually at.", style = RisoType.small)
        Spacer(Modifier.height(8.dp))
        rows.forEach { row ->
            val absent = s.history[row.key].orEmpty().filter { it.status == AttendanceDay.Status.Absent }.sortedByDescending { vtopDate(it.date) }
            InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).printIn(n++), kind = Kind.Attendance, plateOffset = 3.dp) {
                InkText("${row.courseName} · ${if (isLabType(row.classType)) "Lab" else "Theory"}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                absent.forEach { d ->
                    val mark = flagged.firstOrNull { it.course == row.key && it.date == d.date && it.slot == d.slot }
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        InkText("${dayLabel(d.date)} · ${d.slot}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "I was there", true to "On duty").forEach { (od, label) ->
                                val on = mark != null && mark.onDuty == od
                                Box(
                                    Modifier.clickable(role = Role.Button) {
                                        // Switching between the two replaces the mark; tapping the marked one clears it.
                                        if (mark != null) repo.toggleCorrection(row.key, d.date, d.slot, mark.onDuty)
                                        if (!on) repo.toggleCorrection(row.key, d.date, d.slot, od)
                                    },
                                ) { InkChip(if (on) "✓ $label" else label, solid = on) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Problem Solving using Python · Lab", or the key's code if the course has gone. */
private fun courseLabel(s: Snapshot, key: String): String =
    s.attendance.firstOrNull { it.key == key }?.let { "${it.courseName} · ${if (isLabType(it.classType)) "Lab" else "Theory"}" } ?: key.substringBefore('|')

/** "22-Oct-2026" → "Thu 22 Oct". */
internal fun dayLabel(text: String): String =
    vtopDate(text)?.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)) ?: text

/** Hands [text] to the share menu (email, WhatsApp, notes). */
internal fun shareText(context: Context, title: String, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
