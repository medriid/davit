package dev.medriid.davit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.LeaveVerdict
import dev.medriid.davit.data.afterLeave
import dev.medriid.davit.data.effectiveCalendar
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.mustAttend
import dev.medriid.davit.data.percent
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Plan a leave: tap the days you'll be away, see every course after it. Days without
 * classes can't be picked; a day on another weekday's order says which. Assumes you go
 * to every other class until your last day away.
 */
@Composable
internal fun LeavePlannerScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val snapshot = state.snapshot ?: return
    val inks = LocalInks.current
    val today = LocalDate.now()
    val cal = remember(snapshot) { snapshot.effectiveCalendar() }
    val end = cal?.let { listOfNotNull(it.lastTheory, it.lastLab).maxOrNull() } ?: today.plusWeeks(8)
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val dates = picked.map(LocalDate::ofEpochDay).toSet()
    val impacts = remember(snapshot, picked) { snapshot.afterLeave(dates, LocalDateTime.now()) }

    RouteScreen(route, "Plan a leave", "Tap the days you'll be away. Every other class until then counts as attended.") {
        if (cal == null) InkText("No academic calendar yet, so holidays and the semester's end aren't known; each day runs its own weekday.", style = RisoType.small)

        // The months from today to the last teaching day.
        var month = YearMonth.from(today)
        while (!month.atDay(1).isAfter(end)) {
            val m = month
            GroupLabel(m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)), Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { InkText(it, label = true) } }
            }
            val lead = m.atDay(1).dayOfWeek.value - 1
            for (wk in 0 until (lead + m.lengthOfMonth() + 6) / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val n = wk * 7 + col - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                            if (n in 1..m.lengthOfMonth()) {
                                val d = m.atDay(n)
                                val order = if (cal != null) cal.timetableDay(d) else d.dayOfWeek.value.takeIf { it <= 6 }
                                val open = !d.isBefore(today) && !d.isAfter(end) && order != null
                                val on = d in dates
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (on) inks.a else Color.Transparent)
                                        .then(if (d == today) Modifier.border(2.dp, inks.text, RoundedCornerShape(6.dp)) else Modifier)
                                        .clickable(enabled = open, role = Role.Checkbox) {
                                            val e = d.toEpochDay()
                                            picked = if (on) picked - e else picked + e
                                        }
                                        .semantics { selected = on },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        BasicText(
                                            "$n",
                                            style = RisoType.small.copy(
                                                color = if (on) inks.paper else if (open) inks.text else inks.rule,
                                                fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                        )
                                        if (open && order != d.dayOfWeek.value) {
                                            BasicText(
                                                DayOfWeek.of(order!!).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                                                style = RisoType.small.copy(color = if (on) inks.paper else inks.a, fontSize = 9.sp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            month = month.plusMonths(1)
        }

        Spacer(Modifier.height(16.dp))
        if (dates.isEmpty()) {
            InkText("Pick a day to see what it costs.", style = RisoType.small)
            return@RouteScreen
        }
        val missedAll = impacts.sumOf { it.missed }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(
                "${dates.size} day${if (dates.size == 1) "" else "s"} away · $missedAll class${if (missedAll == 1) "" else "es"} missed",
                style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            InkButton("Clear", { picked = emptyList() }, quiet = true)
        }
        if (snapshot.attendanceExempt) InkText("Your CGPA is 9 or above, so the 75% rule doesn't apply to you.", style = RisoType.small)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            impacts.filter { it.missed > 0 }.forEach { i ->
                val bad = i.verdict == LeaveVerdict.Recoverable || i.verdict == LeaveVerdict.OutOfReach
                InkCard(Modifier.fillMaxWidth(), kind = Kind.Attendance, plate = if (bad) inks.a else null, plateOffset = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(i.row.courseName, style = RisoType.title, maxLines = 2)
                            InkText(
                                (if (isLabType(i.row.classType)) "Lab" else "Theory") + " · misses ${i.missed}",
                                style = RisoType.small,
                            )
                        }
                        InkText("${i.row.percent}% → ${i.after.percent}%", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(6.dp))
                    InkText(
                        when (i.verdict) {
                            LeaveVerdict.Safe -> "Still at or above 75% (${i.after.attended}/${i.after.total})."
                            LeaveVerdict.Recoverable -> "Below 75% after. Attend the next ${i.after.mustAttend} to get back" +
                                (i.left?.let { " ($it left this semester)." } ?: ".")
                            LeaveVerdict.OutOfReach -> "Below 75%, and there aren't enough classes left to get back this semester."
                            LeaveVerdict.Exempt -> "${i.after.attended}/${i.after.total} after; the 75% rule doesn't apply to you."
                        },
                        style = RisoType.small,
                    )
                }
            }
            val untouched = impacts.filter { it.missed == 0 }
            if (untouched.isNotEmpty()) {
                InkText(
                    "No classes missed: " + untouched.joinToString(", ") { it.row.courseName + if (isLabType(it.row.classType)) " (lab)" else "" } + ".",
                    style = RisoType.small,
                )
            }
            impacts.filter { !it.inTimetable }.takeIf { it.isNotEmpty() }?.let { off ->
                InkChip("Not in your timetable: " + off.joinToString(", ") { it.row.courseCode })
            }
        }
    }
}
