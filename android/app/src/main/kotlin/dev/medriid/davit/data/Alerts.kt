package dev.medriid.davit.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AlertChannel(val id: String, val title: String) {
    Attendance("attendance", "Attendance"),
    Marks("marks", "New marks"),
    Deadlines("deadlines", "Deadlines and exams"),
}

/** One notification. [key] identifies the event, so it's only ever posted once. */
data class Alert(val channel: AlertChannel, val key: String, val title: String, val text: String)

private val vtopDays = listOf(
    DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
)

/** VTOP's "15-Oct-2026" or "15-10-2026" (sometimes followed by a time); null for "-" or anything else. */
fun vtopDate(text: String): LocalDate? {
    val t = text.trim().substringBefore(' ')
    return vtopDays.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() }
}

/**
 * What a background sync should tell you, from the snapshot before it and after it.
 * Nothing on a first sync ([old] null), so signing in doesn't fire a flood.
 */
fun alertsFor(old: Snapshot?, new: Snapshot, today: LocalDate, reminders: List<Reminder> = emptyList()): List<Alert> {
    if (old == null) return emptyList()
    val out = mutableListOf<Alert>()

    // Your own reminders: today and tomorrow, like assignments.
    for (r in reminders) {
        val days = r.date.toEpochDay() - today.toEpochDay()
        if (days == 0L || days == 1L) {
            out += Alert(AlertChannel.Deadlines, "rem:${r.id}|$days", (if (days == 0L) "Today: " else "Tomorrow: ") + r.title, "Your reminder")
        }
    }

    // Attendance: crossing below 75%, or landing exactly on it with no skips left.
    if (!new.attendanceExempt && old.semester.id == new.semester.id) {
        val before = old.attendance.associateBy { it.courseCode + "|" + it.classType }
        for (row in new.attendance) {
            val prev = before[row.courseCode + "|" + row.classType] ?: continue
            if (prev.attended == row.attended && prev.total == row.total) continue
            val tag = "${row.courseCode}|${row.classType}|${row.attended}/${row.total}"
            when {
                row.atRisk && !prev.atRisk -> out += Alert(
                    AlertChannel.Attendance, "risk:$tag",
                    "${row.courseName} is below 75%",
                    "${row.percent}% (${row.attended}/${row.total}). Attend the next ${row.mustAttend} to get back.",
                )
                !row.atRisk && row.canSkipNow == 0 && prev.canSkipNow > 0 -> out += Alert(
                    AlertChannel.Attendance, "edge:$tag",
                    "No skips left in ${row.courseName}",
                    "${row.attended}/${row.total}. Missing the next class takes you below 75%.",
                )
            }
        }
    }

    // Marks: an item that has a score now and didn't before. Not on the first marks fetch.
    if (old.marks.isNotEmpty()) {
        val seen = old.marks.flatMap { c -> c.items.filter { it.scored != null }.map { c.classId + "|" + it.title } }.toSet()
        for (c in new.marks) for (m in c.items) {
            val scored = m.scored ?: continue
            if ((c.classId + "|" + m.title) in seen) continue
            val score = "${num(scored)}/${m.max?.let(::num) ?: "?"}"
            out += Alert(AlertChannel.Marks, "mark:${c.classId}|${m.title}", "New mark · ${c.courseName}", "${m.title}: $score")
        }
    }

    // Deadlines: unsubmitted assignments due today or tomorrow, exams tomorrow.
    for (c in new.assignments) for (a in c.items) {
        if (a.updated != null) continue
        val due = vtopDate(a.due) ?: continue
        val days = due.toEpochDay() - today.toEpochDay()
        if (days == 0L || days == 1L) {
            out += Alert(
                AlertChannel.Deadlines, "due:${c.classId}|${a.title}|${a.due}|$days",
                (if (days == 0L) "Due today: " else "Due tomorrow: ") + a.title,
                c.courseName + " · not submitted yet",
            )
        }
    }
    for (e in new.exams) {
        val day = vtopDate(e.date) ?: continue
        if (day.toEpochDay() - today.toEpochDay() != 1L) continue
        out += Alert(
            AlertChannel.Deadlines, "exam:${e.classId}|${e.type}|${e.date}",
            "${e.type.ifBlank { "Exam" }} tomorrow: ${e.courseName}",
            listOf(e.time, e.venue, e.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" })
                .filter { !it.isNullOrBlank() }.joinToString(" · "),
        )
    }
    return out
}

/** "8.5", "10", never "10.0". */
internal fun num(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ENGLISH, v).trimEnd('0').trimEnd('.')
