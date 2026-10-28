package dev.medriid.davit.ui

import java.time.ZoneId
import java.time.LocalTime
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import android.provider.CalendarContract
import android.content.Intent
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.medriid.davit.data.GradePoints
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.neededAverage
import dev.medriid.davit.data.projectCgpa
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.vtop.Assignment
import dev.medriid.davit.vtop.CourseMarks
import dev.medriid.davit.vtop.Exam
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Marks, exams and grades for the selected semester, as three segments. */
@Composable
internal fun AcademicsTab(snapshot: Snapshot) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    TabColumn {
        Heading("Academics", 28.sp, Modifier.padding(top = 8.dp, bottom = 14.dp))
        ChoiceRow(listOf("Marks", "Assignments", "Exams", "Grades"), segment) { segment = it }
        Spacer(Modifier.height(14.dp))
        when (segment) {
            0 -> MarksSegment(snapshot)
            1 -> AssignmentsSegment(snapshot)
            2 -> ExamsSegment(snapshot.exams)
            else -> GradesSegment(snapshot)
        }
    }
}

// ---- marks -------------------------------------------------------------------------

@Composable
private fun MarksSegment(snapshot: Snapshot) {
    if (snapshot.marks.isEmpty()) {
        InkText("No marks for ${snapshot.semester.name} yet.")
        return
    }
    // Courses with marks first, then the rest, each group in VTOP's order.
    val courses = snapshot.marks.sortedBy { it.items.none { m -> m.weighted != null } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        courses.forEach { CourseMarksCard(it) }
    }
}

@Composable
private fun CourseMarksCard(course: CourseMarks) {
    val inks = LocalInks.current
    val counted = course.items.filter { it.weighted != null && it.weightage != null }
    val got = counted.sumOf { it.weighted!! }
    val outOf = counted.sumOf { it.weightage!! }
    InkCard(Modifier.fillMaxWidth(), plate = inks.b, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(course.courseName, style = RisoType.title, maxLines = 2)
                InkText("${course.courseCode} · ${course.courseType}", style = RisoType.small, maxLines = 1)
            }
            if (counted.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Heading(fmt(got), 30.sp)
                    InkText("of ${fmt(outOf)}", style = RisoType.small)
                }
            }
        }
        if (course.items.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            InkText("Nothing marked yet.", style = RisoType.small)
            return@InkCard
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        course.items.forEach { m ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    InkText(m.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    val note = listOf(m.status.takeIf { it.isNotBlank() && !it.equals("Present", true) }, m.remark.ifBlank { null })
                        .filterNotNull().joinToString(" · ")
                    if (note.isNotEmpty()) InkText(note, style = RisoType.small)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    InkText(
                        if (m.scored == null) "–" else "${fmt(m.scored!!)}/${m.max?.let(::fmt) ?: "?"}",
                        style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (m.weightage != null) {
                        InkText("${m.weighted?.let(::fmt) ?: "–"} of ${fmt(m.weightage!!)}", style = RisoType.small)
                    }
                }
            }
        }
    }
}

// ---- assignments -------------------------------------------------------------------

internal fun Assignment.dueDay(): LocalDate? = vtopDate(due)

@Composable
private fun AssignmentsSegment(snapshot: Snapshot) {
    val courses = snapshot.assignments.filter { it.items.isNotEmpty() }
    if (courses.isEmpty()) {
        InkText("No assignments for ${snapshot.semester.name} yet.")
        return
    }
    val today = LocalDate.now()
    // Courses with something still to hand in come first, soonest due first.
    val ordered = courses.sortedWith(
        compareBy(nullsLast()) { c ->
            c.items.filter { it.updated == null }.mapNotNull { it.dueDay() }.filter { !it.isBefore(today) }.minOrNull()
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ordered.forEach { c ->
            InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
                InkText(c.courseName, style = RisoType.title, maxLines = 2)
                InkText("${c.courseCode} · ${c.courseType}", style = RisoType.small, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Rule()
                c.items.forEach { a ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(a.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            val bits = listOfNotNull(
                                a.dueDay()?.let { "Due ${it.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}" },
                                a.weightage?.let { "${fmt(it)}%" },
                            )
                            if (bits.isNotEmpty()) InkText(bits.joinToString(" · "), style = RisoType.small)
                        }
                        Spacer(Modifier.width(10.dp))
                        AssignmentChip(a, today)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssignmentChip(a: Assignment, today: LocalDate) {
    val day = a.dueDay()
    val days = day?.let { ChronoUnit.DAYS.between(today, it).toInt() }
    when {
        a.updated != null -> InkChip("Submitted")
        days == null -> InkChip("No date")
        days < 0 -> InkChip("Overdue", solid = true)
        days == 0 -> InkChip("Today", solid = true)
        days == 1 -> InkChip("Tomorrow", solid = true)
        else -> InkChip("$days days", solid = days <= 7)
    }
}

// ---- exams -------------------------------------------------------------------------

internal fun Exam.day(): LocalDate? = vtopDate(date)

@Composable
private fun ExamsSegment(exams: List<Exam>) {
    if (exams.isEmpty()) {
        InkText("No exams scheduled yet.")
        return
    }
    val today = LocalDate.now()
    val (done, upcoming) = exams.partition { e -> e.day()?.isBefore(today) == true }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        upcoming.sortedWith(compareBy(nullsLast()) { it.day() }).groupBy { it.type }.forEach { (type, list) ->
            InkText(type.ifBlank { "Exams" }, label = true, modifier = Modifier.padding(top = 6.dp))
            list.forEach { ExamCard(it, today) }
        }
        if (done.isNotEmpty()) {
            InkText("Done", label = true, modifier = Modifier.padding(top = 10.dp))
            done.sortedByDescending { it.day() }.forEach { ExamCard(it, today, Modifier.alpha(0.55f)) }
        }
    }
}

@Composable
internal fun ExamCard(exam: Exam, today: LocalDate, modifier: Modifier = Modifier) {
    val day = exam.day()
    val days = day?.let { ChronoUnit.DAYS.between(today, it).toInt() }
    InkCard(modifier.fillMaxWidth(), plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp)) {
                Heading(day?.dayOfMonth?.toString() ?: "?", 30.sp)
                InkText(day?.format(DateTimeFormatter.ofPattern("MMM · EEE", Locale.ENGLISH)) ?: exam.date, style = RisoType.small)
            }
            Column(Modifier.weight(1f)) {
                InkText(exam.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(listOf(exam.time, exam.venue).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                if (exam.seat.isNotBlank()) InkText("Seat ${exam.seat}", style = RisoType.small)
                if (days != null && days >= 0) {
                    val context = LocalContext.current
                    Box(Modifier.padding(top = 6.dp).clickable(role = Role.Button) { addToCalendar(context, exam) }) {
                        InkChip("Add to calendar")
                    }
                }
            }
            if (days != null && days >= 0) {
                Spacer(Modifier.width(8.dp))
                InkChip(
                    when (days) {
                        0 -> "Today"
                        1 -> "Tomorrow"
                        else -> "$days days"
                    },
                    solid = days <= 7,
                )
            }
        }
    }
}

/** Opens the calendar app with the exam filled in; all-day if VTOP's time can't be read. */
private fun addToCalendar(context: Context, exam: Exam) {
    val day = exam.day() ?: return
    val clock = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH)
    val times = exam.time.split("-").map { it.trim().uppercase() }
    val start = times.getOrNull(0)?.let { runCatching { LocalTime.parse(it, clock) }.getOrNull() }
    val end = times.getOrNull(1)?.let { runCatching { LocalTime.parse(it, clock) }.getOrNull() }
    val zone = ZoneId.systemDefault()
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, "${exam.type.ifBlank { "Exam" }}: ${exam.courseName}")
        .putExtra(CalendarContract.Events.EVENT_LOCATION, exam.venue)
        .putExtra(CalendarContract.Events.DESCRIPTION, listOf(exam.courseCode, exam.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" }).filterNotNull().joinToString(" · "))
    if (start != null) {
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, day.atTime(start).atZone(zone).toInstant().toEpochMilli())
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, day.atTime(end ?: start.plusHours(1)).atZone(zone).toInstant().toEpochMilli())
    } else {
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, day.atStartOfDay(zone).toInstant().toEpochMilli())
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---- grades ------------------------------------------------------------------------

@Composable
private fun GradesSegment(snapshot: Snapshot) {
    val g = snapshot.grades
    val heroSize = with(LocalDensity.current) { 56.dp.toSp() }
    InkCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText("CGPA", label = true)
                Spacer(Modifier.height(8.dp))
                g?.creditsEarned?.let { InkText("${fmt(it)} credits earned", style = RisoType.small) }
                Spacer(Modifier.height(10.dp))
                InkChip(if (snapshot.attendanceExempt) "Exempt from 75% rule" else "75% attendance rule applies", solid = !snapshot.attendanceExempt)
            }
            // Two decimals, cut not rounded: 8.996 must not read as 9.00.
            Overprint(g?.cgpa?.let { "%.2f".format(Locale.ENGLISH, Math.floor(it * 100) / 100) } ?: "–", heroSize)
        }
    }
    if (g?.curriculum?.isNotEmpty() == true) {
        SectionTitle("Credits")
        InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
            g.curriculum.forEachIndexed { i, d ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row {
                    InkText(d.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 1)
                    InkText("${fmt(d.earned)} / ${fmt(d.required)}", style = RisoType.small)
                }
                Spacer(Modifier.height(4.dp))
                HalftoneBar(if (d.required > 0) (d.earned / d.required).toFloat().coerceIn(0f, 1f) else 1f, height = 8.dp)
            }
        }
    }

    PlannerCard(snapshot)

    val graded = g?.entries.orEmpty()
    if (graded.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        InkText(if (g == null) "Grades arrive with the next sync." else "No grades yet. They appear after your first semester's results.")
        return
    }
    graded.groupBy { it.examMonth }.entries.reversed().forEach { (month, list) ->
        SectionTitle(month.ifBlank { "Courses" })
        InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
            list.forEachIndexed { i, e ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(e.courseTitle, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                        InkText("${e.courseCode} · ${fmt(e.credits)} credits", style = RisoType.small)
                    }
                    Spacer(Modifier.width(10.dp))
                    InkChip(e.grade ?: "–", solid = e.grade != null)
                }
            }
        }
    }
}

/**
 * "Plan this semester": a grade per current course gives the CGPA it would lead to, and
 * the average needed for 9, which lifts the 75% attendance rule.
 */
@Composable
private fun PlannerCard(snapshot: Snapshot) {
    val courses = snapshot.timetable.filter { it.credits > 0 }.distinctBy { it.courseCode + "|" + it.courseType }
    if (courses.isEmpty()) return
    // Grade index per course; A by default. Not saved: it's a scratchpad.
    val picks = remember(courses.size) { mutableStateListOf(*Array(courses.size) { 1 }) }
    val planned = courses.mapIndexed { i, c -> c.credits to GradePoints[picks[i]].second }
    val projected = projectCgpa(snapshot.grades, planned)
    val needed = neededAverage(snapshot.grades, courses.sumOf { it.credits })
    val heroSize = with(LocalDensity.current) { 44.dp.toSp() }

    SectionTitle("Plan this semester")
    InkCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText("CGPA if these hold", label = true)
                Spacer(Modifier.height(8.dp))
                InkText(
                    when {
                        needed == null -> ""
                        needed <= 0 -> "You're above 9 whatever happens."
                        needed > 10 -> "9 is out of reach this semester."
                        else -> "Needs an average of ${"%.2f".format(Locale.ENGLISH, needed)} for 9."
                    },
                    style = RisoType.small,
                )
            }
            Overprint(projected?.let { "%.2f".format(Locale.ENGLISH, Math.floor(it * 100) / 100) } ?: "–", heroSize)
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        courses.forEachIndexed { i, c ->
            Spacer(Modifier.height(10.dp))
            InkText(c.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
            InkText("${fmt(c.credits)} credits", style = RisoType.small)
            ChoiceRow(GradePoints.map { it.first }, picks[i]) { picks[i] = it }
        }
    }
}

/** "12.3", "15", never "15.0". */
private fun fmt(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ENGLISH, v).trimEnd('0').trimEnd('.')
