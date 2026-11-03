package dev.medriid.davit.ui

import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.data.ClassSlot
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.TodayPlan
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.canSkipNow
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.mustAttend
import dev.medriid.davit.data.overallPercent
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.remainingFor
import dev.medriid.davit.data.skipTillEnd
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.TiltedCard
import dev.medriid.davit.design.printIn
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today: what you need to know right now, most pressing first. Your class now and next
 * (room a tap from the map), the counts, an exam or registration that's close, the
 * course most at risk, what's due, what changed; VTOP's remarks and feedback last.
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
    onOpen: (Route) -> Unit = {},
) {
    // The Soft look has its own Home.
    if (LocalInks.current.editorial) {
        EditorialHome(snapshot, reminders, onOpen, onOpenAttendance, onOpenWeek, onOpenAcademics)
        return
    }
    if (LocalInks.current.soft) {
        SoftHome(snapshot, reminders, onOpen, onOpenWeek, onOpenAttendance)
        return
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = LocalDateTime.now()
        }
    }
    val week = remember(snapshot) { weekOf(snapshot) }
    val plan = planFor(week, now, snapshot.calendar)
    val calDay = plan.calendarDay
    val att = snapshot.attendance
    val risky = att.count { it.atRisk }
    val overall = overallPercent(att)

    TabColumn {
        // The day in one panel: the date, today's classes as a path you can swipe along, whichever
        // class is in the middle, and the next class once today's are over.
        InkCard(
            Modifier.fillMaxWidth(),
            kind = dev.medriid.davit.design.Kind.Classes,
            plateOffset = 4.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            InkText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), style = RisoType.title.copy(fontSize = 19.sp), maxLines = 1)
            InkText(
                listOfNotNull(
                    when {
                        calDay != null && !calDay.instructional -> listOfNotNull(calDay.type, calDay.note).joinToString(" · ")
                        calDay != null && calDay.dayOrder in 1..7 && calDay.dayOrder != now.dayOfWeek.value ->
                            "Follows ${DayOfWeek.of(calDay.dayOrder).getDisplayName(TextStyle.FULL, Locale.ENGLISH)}'s timetable"
                        else -> null
                    },
                    if (plan.today.isEmpty()) "No classes today" else "${plan.done}/${plan.today.size} done",
                    snapshot.calendar?.instructionalDaysLeft(now.toLocalDate())?.takeIf { it > 0 }?.let { "$it teaching day${if (it == 1) "" else "s"} left" },
                ).joinToString(" · "),
                style = RisoType.small,
                maxLines = 2,
            )
            if (plan.today.isNotEmpty()) {
                val minute = now.hour * 60 + now.minute
                val current = plan.today.indexOf(plan.now).takeIf { it >= 0 }
                    ?: plan.today.indexOfFirst { it.meeting.start > minute }.takeIf { it >= 0 }
                val focus = current ?: plan.today.lastIndex
                var picked by remember(focus, plan.today) { mutableIntStateOf(focus) }
                // Once the day's classes are over, the last one only repeats the path: it waits for a tap.
                var opened by remember(focus, plan.today) { mutableStateOf(current != null) }
                DayPath(
                    plan.today, minute, picked,
                    onSelect = {
                        picked = it
                        if (it != focus) opened = true
                    },
                    bleed = 12.dp,
                    onTap = {
                        picked = it
                        opened = true
                    },
                )
                if (opened) {
                    Spacer(Modifier.height(12.dp))
                    val slot = plan.today[picked]
                    PickedClass(
                        slot, now, slot == plan.next && plan.nextInDays == 0,
                        slot.attendance?.let { snapshot.remainingFor(it, now) },
                        if (slot == plan.now) onOpenAttendance else onOpenWeek,
                    )
                }
            }
            // Nothing left today: the next class on a later day.
            plan.next?.takeIf { plan.nextInDays > 0 }?.let {
                if (plan.today.isNotEmpty()) Rule(Modifier.padding(vertical = 8.dp)) else Spacer(Modifier.height(10.dp))
                NextClass(it, plan, it.attendance?.let { a -> snapshot.remainingFor(a, now) }, showEnd = plan.today.isEmpty(), onOpenWeek)
            }
            if (snapshot.timetable.isEmpty()) {
                InkText("No timetable for ${snapshot.semester.name} yet, so no Now/Next.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                overall?.let { "$it%" } ?: "–",
                "overall",
                Modifier.weight(1f).fillMaxHeight(),
                onOpenAttendance,
            )
            StatTile("$risky", "at risk", Modifier.weight(1f).fillMaxHeight(), onOpenAttendance)
        }

        // Registration opening within a day is urgent; further off, it waits lower down.
        val reg = snapshot.feeds.nextRegistration(now)
        val regSoon = reg?.start != null && java.time.Duration.between(now, reg.start).toHours() < 24
        if (reg?.start != null && regSoon) {
            Spacer(Modifier.height(14.dp))
            RegistrationCard(reg, now, Modifier.printIn(2))
        }

        Spacer(Modifier.height(12.dp))
        QuickButtons(onOpenAcademics, onOpen)

        val today = now.toLocalDate()
        val nextExam = snapshot.exams
            .mapNotNull { e -> e.day()?.let { e to it } }
            .filter { (_, d) -> !d.isBefore(today) && d.toEpochDay() - today.toEpochDay() <= 14 }
            .minByOrNull { (_, d) -> d }?.first
        if (nextExam != null) {
            SectionTitle(nextExam.type.ifBlank { "Exam" })
            val nav = LocalNav.current
            ExamCard(nextExam, today, onClick = { nav.go(Route.ExamBoard) })
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
                    InkCard(Modifier.fillMaxWidth(), onClick = onOpenAcademics, kind = dev.medriid.davit.design.Kind.Assignments, plateOffset = 4.dp) {
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
            var allDue by rememberSaveable { mutableStateOf(false) }
            due.sortedBy { it.first }.take(if (allDue) due.size else 3).forEach { it.second() }
            if (due.size > 3) ShowMore(allDue, due.size - 3) { allDue = !allDue }
        }

        if (snapshot.changes.isNotEmpty()) {
            SectionTitle("Since you last looked")
            var allChanges by rememberSaveable { mutableStateOf(false) }
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 4.dp) {
                snapshot.changes.take(if (allChanges) 8 else 2).forEachIndexed { i, change ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    InkText(change)
                }
                val more = snapshot.changes.take(8).size - 2
                if (more > 0) {
                    Spacer(Modifier.height(8.dp))
                    ShowMore(allChanges, more) { allChanges = !allChanges }
                }
            }
        }

        if (reg?.start != null && !regSoon && java.time.Duration.between(now, reg.start).toDays() <= 3) {
            Spacer(Modifier.height(22.dp))
            RegistrationCard(reg, now)
        }

        val events = snapshot.feeds.eventsOn(today) + snapshot.feeds.eventsOn(today.plusDays(1))
        if (events.isNotEmpty()) {
            SectionTitle("Club events")
            InkCard(Modifier.fillMaxWidth().sharedCard(Route.Events.sharedKey), onClick = { onOpen(Route.Events) }, kind = dev.medriid.davit.design.Kind.Events, plateOffset = 4.dp) {
                events.take(2).forEachIndexed { i, e ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    InkText(e.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    InkText((if (e.date == today) "Today" else "Tomorrow") + " · " + listOf(e.category, e.club).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 1)
                }
                if (events.size > 2) InkText("+ ${events.size - 2} more", style = RisoType.small, modifier = Modifier.padding(top = 6.dp))
            }
        }

        snapshot.feeds.proctorNote?.let { note ->
            SectionTitle("From your proctor")
            InkCard(Modifier.fillMaxWidth(), onClick = { onOpen(Route.Messages) }, kind = dev.medriid.davit.design.Kind.People, plateOffset = 4.dp) { InkText(note, maxLines = 2) }
        }

        // VTOP's own remarks (debarment, revoked exams) and pending feedback: worth knowing,
        // rarely news by the time you open the app. Notifications tell you when they change.
        val serious = snapshot.feeds.remarks.filter { it.serious }
        val pending = snapshot.feeds.pendingFeedback
        if (serious.isNotEmpty() || pending.isNotEmpty()) SectionTitle("VTOP remarks")
        if (serious.isNotEmpty()) {
            InkCard(Modifier.fillMaxWidth(), onClick = onOpenAttendance, kind = dev.medriid.davit.design.Kind.Attendance, plate = LocalInks.current.a, plateOffset = 4.dp) {
                InkText(if (serious.any { it.debarred }) "Debarred" else "Revoked", label = true)
                Spacer(Modifier.height(8.dp))
                serious.forEach { r ->
                    InkText(r.name.ifBlank { r.code } + " · " + r.type, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    InkText(r.remark + (r.attendance?.let { " · VTOP shows ${fmtNum(it)}%" } ?: ""), style = RisoType.small)
                    Spacer(Modifier.height(6.dp))
                }
                InkText("Talk to your faculty or proctor about it.", style = RisoType.small)
            }
        }
        if (pending.isNotEmpty()) {
            if (serious.isNotEmpty()) Spacer(Modifier.height(12.dp))
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 4.dp) {
                InkText("${pending.size} feedback form${if (pending.size > 1) "s" else ""} pending", label = true)
                Spacer(Modifier.height(6.dp))
                InkText("Fill them on VTOP: a pending mandatory one can lock VTOP's menus.", style = RisoType.small)
            }
        }
    }
}

/** The ways in you use most, two short rows, so nothing needs a long scroll. */
@Composable
private fun QuickButtons(onOpenAcademics: () -> Unit, onOpen: (Route) -> Unit) {
    val repo = LocalRepo.current
    fun academics(section: Int): () -> Unit = {
        repo.academicsFocus.value = section
        onOpenAcademics()
    }
    val rows = listOf(
        listOf(
            Triple("Marks", RisoIcon.Marks, academics(0)),
            Triple("Assignments", RisoIcon.Assignments, academics(1)),
            Triple("Materials", RisoIcon.Materials, academics(4)),
        ),
        listOf(
            Triple("Exam board", RisoIcon.Exams) { onOpen(Route.ExamBoard) },
            Triple("Mess menu", RisoIcon.Today) { onOpen(Route.MessMenu) },
            Triple("More", RisoIcon.More) { onOpen(Route.Campus) },
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, icon, go) ->
                    InkCard(Modifier.weight(1f).height(62.dp), onClick = go, plateOffset = 3.dp, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        IconGlyph(icon, LocalInks.current.text, Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        InkText(label, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowMore(open: Boolean, more: Int, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp)) {
        InkChip(if (open) "Show less" else "$more more")
    }
}

/** The registration window: when it opens, how long it's open, and a countdown. */
@Composable
private fun RegistrationCard(w: dev.medriid.davit.vtop.RegistrationWindow, now: LocalDateTime, modifier: Modifier = Modifier) {
    val start = w.start ?: return
    val live = !now.isBefore(start) && (w.end == null || now.isBefore(w.end))
    val mins = java.time.Duration.between(now, start).toMinutes()
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    val clockF = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    InkCard(modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Reminders, plate = if (live || mins < 24 * 60) LocalInks.current.a else null, plateOffset = 5.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText("Registration", label = true)
                Spacer(Modifier.height(6.dp))
                InkText(w.semester.ifBlank { "Course registration" }, style = RisoType.title.copy(fontSize = 18.sp), maxLines = 2)
                // "Sun 1 Nov · 5:00–5:45 PM": one AM/PM when both ends share it.
                val end = w.end
                val sameHalf = end != null && (start.hour < 12) == (end.hour < 12) && start.toLocalDate() == end.toLocalDate()
                InkText(
                    start.format(day) + " · " +
                        (if (sameHalf) start.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)) else start.format(clockF)) +
                        (end?.let { "–" + it.format(clockF) } ?: ""),
                    style = RisoType.small.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.width(10.dp))
            InkChip(
                when {
                    live -> "Open now"
                    mins < 60 -> "in $mins min"
                    mins < 24 * 60 -> "in ${mins / 60} h"
                    else -> "in ${mins / (24 * 60)} days"
                },
                solid = live || mins < 24 * 60,
            )
        }
        Spacer(Modifier.height(8.dp))
        InkText("Register on VTOP; daVIT reminds you 30 minutes before.", style = RisoType.small)
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    InkCard(modifier, onClick = onClick, kind = dev.medriid.davit.design.Kind.Attendance, plateOffset = 3.dp, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            InkText(value, style = RisoType.title.copy(fontSize = 20.sp), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            InkText(label, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** The class picked on the day path: what it is, where, and where its attendance stands. */
@Composable
private fun PickedClass(slot: ClassSlot, now: LocalDateTime, isNext: Boolean, remaining: Int?, onClick: () -> Unit) {
    val minute = now.hour * 60 + now.minute
    val m = slot.meeting
    val mins = m.start - minute
    val status = when {
        m.end <= minute -> "Done"
        m.start <= minute -> "On now"
        else -> (if (isNext) "Next, " else "Later, ") + if (mins < 60) "in $mins min" else "in ${mins / 60} h ${mins % 60} min"
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(slot.course.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
            if (slot.course.venue.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                dev.medriid.davit.ui.map.MapChip(slot.course.venue)
            }
        }
        InkText("$status · ${if (slot.isLab) "Lab" else "Theory"}", style = RisoType.small, maxLines = 1)
        slot.attendance?.let { a ->
            Spacer(Modifier.height(5.dp))
            HalftoneBar(if (a.total == 0) 1f else a.attended.toFloat() / a.total, threshold = 0.75f, atRisk = a.atRisk, height = 8.dp)
            Spacer(Modifier.height(3.dp))
            InkText("${a.percent}% · ${a.hint(remaining)}", style = RisoType.small, maxLines = 2)
        }
    }
}

/** The next class when none are left today. With no path above it ([showEnd]), this is the only place its times show. */
@Composable
private fun NextClass(slot: ClassSlot, plan: TodayPlan, remaining: Int?, showEnd: Boolean, onClick: () -> Unit) {
    val day = when (plan.nextInDays) {
        1 -> "tomorrow"
        else -> (plan.nextDate?.dayOfWeek ?: DayOfWeek.of(slot.meeting.day)).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText("Next · $day ${clock(slot.meeting.start)}" + if (showEnd) " – ${clock(slot.meeting.end)}" else "", label = true, maxLines = 1)
                InkText(slot.course.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
            if (slot.course.venue.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                dev.medriid.davit.ui.map.MapChip(slot.course.venue)
            }
        }
        InkText(
            listOfNotNull(if (slot.isLab) "Lab" else "Theory", slot.attendance?.let { "${it.percent}% · ${it.hint(remaining)}" }).joinToString(" · "),
            style = RisoType.small,
            maxLines = 2,
        )
    }
}

/** One-tap titles for the usual things. */
private val ReminderPresets = listOf("Mess change", "Go to Allmart", "Offline quiz", "Project review", "Report submission")

private val dueFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun ReminderCard(r: Reminder, today: LocalDate, onDone: () -> Unit) {
    val days = r.date.toEpochDay() - today.toEpochDay()
    InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Reminders, plateOffset = 4.dp) {
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
        ChoiceRow(ReminderPresets, ReminderPresets.indexOf(title)) { title = ReminderPresets[it] }
        Spacer(Modifier.height(8.dp))
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
