package dev.medriid.davit.ui

import java.time.LocalDate
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.data.Reminder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedVisibility
import android.app.DatePickerDialog
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.ClassSlot
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.TodayPlan
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.canSkipNow
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.mustAttend
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.TiltedCard
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today: what you need to know right now. In order: date and
 * counts, NOW (the hero), NEXT, one at-risk callout, what changed. "Due" waits for the
 * M4 scrapers.
 */
@Composable
internal fun TodayTab(
    snapshot: Snapshot,
    reminders: List<Reminder>,
    onOpenAttendance: () -> Unit,
    onOpenWeek: () -> Unit,
    onOpenAcademics: () -> Unit,
    onAddReminder: (String, LocalDate) -> Unit,
    onRemoveReminder: (Long) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = LocalDateTime.now()
        }
    }
    val week = remember(snapshot) { weekOf(snapshot) }
    val plan = planFor(week, now)
    val att = snapshot.attendance
    val risky = att.count { it.atRisk }
    val attended = att.sumOf { it.attended }
    val total = att.sumOf { it.total }

    TabColumn {
        InkText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = RisoType.title)
        InkText(
            when (plan.today.size) {
                0 -> "No classes today"
                else -> "${plan.today.size} class${if (plan.today.size > 1) "es" else ""} today · ${plan.done} done"
            },
            style = RisoType.small,
        )
        Spacer(Modifier.height(16.dp))

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                if (total == 0) "–" else "${attended * 100 / total}%",
                "overall",
                Modifier.weight(1f).fillMaxHeight(),
                onOpenAttendance,
            )
            StatTile("$risky", "at risk", Modifier.weight(1f).fillMaxHeight(), onOpenAttendance)
        }

        if (plan.now != null) {
            SectionTitle("Now")
            NowCard(plan.now, now, onOpenAttendance)
            Spacer(Modifier.height(14.dp))
        } else if (plan.today.isNotEmpty() || plan.next != null) {
            Spacer(Modifier.height(20.dp))
        }
        plan.next?.let { NextCard(it, plan, now, onOpenWeek) }
        if (snapshot.timetable.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            InkText("No timetable for ${snapshot.semester.name} yet, so no Now/Next.", style = RisoType.small)
        }

        val today = now.toLocalDate()
        val nextExam = snapshot.exams
            .mapNotNull { e -> e.day()?.let { e to it } }
            .filter { (_, d) -> !d.isBefore(today) && d.toEpochDay() - today.toEpochDay() <= 14 }
            .minByOrNull { (_, d) -> d }?.first
        if (nextExam != null) {
            SectionTitle(nextExam.type.ifBlank { "Exam" })
            ExamCard(nextExam, today)
        }

        val worst = att.filter { it.atRisk }.minByOrNull { it.percent }.takeUnless { snapshot.attendanceExempt }
        if (worst != null) {
            Spacer(Modifier.height(30.dp))
            TiltedCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenAttendance)) {
                Column {
                    InkText("At risk", label = true)
                    Spacer(Modifier.height(10.dp))
                    Heading("${worst.courseName} ${worst.percent}%", 26.sp)
                    Spacer(Modifier.height(10.dp))
                    InkText("Attend the next ${worst.mustAttend} to get back to 75%.")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Due: assignments still to hand in over the next two weeks, and your own reminders.
        val assignmentsDue = snapshot.assignments
            .flatMap { c -> c.items.map { c to it } }
            .filter { (_, a) -> a.updated == null }
            .mapNotNull { (c, a) -> a.dueDay()?.let { Triple(c, a, it) } }
            .filter { (_, _, d) -> !d.isBefore(today) && d.toEpochDay() - today.toEpochDay() <= 14 }
        val due: List<Pair<LocalDate, @Composable () -> Unit>> =
            assignmentsDue.map { (c, a, d) ->
                d to @Composable {
                    InkCard(Modifier.fillMaxWidth(), onClick = onOpenAcademics, plateOffset = 4.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                InkText(a.title, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 2)
                                InkText("${c.courseName} · ${d.format(dueFormat)}", style = RisoType.small, maxLines = 2)
                            }
                            Spacer(Modifier.width(10.dp))
                            AssignmentChip(a, today)
                        }
                    }
                }
            } + reminders.filter { it.date.toEpochDay() - today.toEpochDay() <= 30 }.map { r ->
                r.date to @Composable { ReminderCard(r, today) { onRemoveReminder(r.id) } }
            }
        Row(Modifier.padding(top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading("Due", 22.sp, Modifier.weight(1f))
            Box(Modifier.clickable(role = Role.Button) { adding = !adding }.padding(4.dp)) {
                InkChip(if (adding) "Cancel" else "+ Add", solid = !adding)
            }
        }
        AnimatedVisibility(adding) {
            AddReminder { title, date ->
                onAddReminder(title, date)
                adding = false
            }
        }
        if (due.isEmpty() && !adding) InkText("Nothing due in the next two weeks.", style = RisoType.small)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            due.sortedBy { it.first }.take(6).forEach { it.second() }
            if (due.size > 6) InkText("+ ${due.size - 6} more", style = RisoType.small)
        }

        if (snapshot.changes.isNotEmpty()) {
            SectionTitle("Since you last looked")
            InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
                snapshot.changes.take(8).forEachIndexed { i, change ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    InkText(change)
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    InkCard(modifier, onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        Heading(value, 40.sp)
        Spacer(Modifier.height(10.dp))
        InkText(label, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun NowCard(slot: ClassSlot, now: LocalDateTime, onClick: () -> Unit) {
    val inks = LocalInks.current
    val m = slot.meeting
    val minute = now.hour * 60 + now.minute + now.second / 60f
    val row = slot.attendance
    val hero by animateIntAsState(row?.percent ?: 0, label = "hero")
    val heroSize = with(LocalDensity.current) { 50.dp.toSp() }

    InkCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkText(slot.course.courseName, style = RisoType.title.copy(fontSize = 21.sp), modifier = Modifier.weight(1f), maxLines = 2)
                    InkChip("Live", solid = true)
                }
                InkText("${slot.course.courseCode} · ${if (slot.isLab) "Lab" else "Theory"}", style = RisoType.small)
                Spacer(Modifier.height(12.dp))
                InkText("${clock(m.start)} – ${clock(m.end)}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                HalftoneBar(((minute - m.start) / (m.end - m.start)).coerceIn(0f, 1f), height = 10.dp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (slot.course.venue.isNotBlank()) InkChip(slot.course.venue)
                    InkChip(m.slot)
                }
            }
            if (row != null) {
                Spacer(Modifier.width(14.dp))
                Spacer(Modifier.width(2.dp).fillMaxHeight().background(inks.text))
                Column(
                    Modifier.width(118.dp).fillMaxHeight().padding(start = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Overprint("$hero%", heroSize)
                    Spacer(Modifier.height(14.dp))
                    if (row.atRisk) {
                        InkText("attend ${row.mustAttend}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        InkText("to recover", style = RisoType.small)
                    } else {
                        InkText("skip ${row.canSkipNow}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        InkText("and stay at 75%", style = RisoType.small)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextCard(slot: ClassSlot, plan: TodayPlan, now: LocalDateTime, onClick: () -> Unit) {
    val minute = now.hour * 60 + now.minute
    val whenText = when (plan.nextInDays) {
        0 -> {
            val mins = slot.meeting.start - minute
            if (mins < 60) "in $mins min" else "in ${mins / 60} h ${mins % 60} min"
        }
        1 -> "tomorrow"
        else -> DayOfWeek.of(slot.meeting.day).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }
    InkCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        plateOffset = 4.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText("Next", label = true, modifier = Modifier.width(52.dp))
            InkText(clock(slot.meeting.start), style = RisoType.title, modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                InkText(slot.course.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                val tail = listOfNotNull(whenText, if (slot.isLab) "Lab" else "Theory", slot.course.venue.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                InkText(tail, style = RisoType.small)
                slot.attendance?.let { InkText("${it.percent}% · ${it.hint()}", style = RisoType.small) }
            }
        }
    }
}

private val dueFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun ReminderCard(r: Reminder, today: LocalDate, onDone: () -> Unit) {
    val days = r.date.toEpochDay() - today.toEpochDay()
    InkCard(Modifier.fillMaxWidth(), plate = LocalInks.current.b, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(r.title, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 2)
                InkText(
                    "Your reminder · ${r.date.format(dueFormat)} · " + when {
                        days < 0 -> "overdue"
                        days == 0L -> "today"
                        days == 1L -> "tomorrow"
                        else -> "in $days days"
                    },
                    style = RisoType.small,
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clickable(role = Role.Button, onClick = onDone).padding(4.dp)) { InkChip("Done") }
        }
    }
}

/** Title and a date from the system picker; Save adds it. */
@Composable
private fun AddReminder(onSave: (String, LocalDate) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    Column(Modifier.padding(bottom = 14.dp)) {
        InkField(title, { title = it.take(80) }, "Reminder", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(date.format(dueFormat), style = RisoType.title, modifier = Modifier.weight(1f))
            InkButton("Change date", {
                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }, quiet = true)
        }
        Spacer(Modifier.height(10.dp))
        InkButton("Save", { onSave(title, date) }, enabled = title.isNotBlank(), fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}
