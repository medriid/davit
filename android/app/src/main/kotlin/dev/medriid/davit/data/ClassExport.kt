package dev.medriid.davit.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Classes are in campus time, whatever zone the phone is in. */
val CampusZone: ZoneId = ZoneId.of("Asia/Kolkata")

/** One class on one date, for the phone's calendar. [uid] stays the same every time it's built. */
data class ClassEvent(
    val uid: String,
    val title: String,
    val location: String,
    val notes: String,
    val date: LocalDate,
    /** Minutes from midnight. */
    val start: Int,
    val end: Int,
) {
    val startAt: ZonedDateTime get() = ZonedDateTime.of(date, LocalTime.of(start / 60, start % 60), CampusZone)
    val endAt: ZonedDateTime get() = ZonedDateTime.of(date, LocalTime.of(end / 60, end % 60), CampusZone)
}

/**
 * Every class from [from] to the end of the semester, one event per class per date: a
 * Saturday on Wednesday's order gets Wednesday's classes, holidays and exam days none,
 * labs stop at the last lab day and theory at the last theory day. Empty without a
 * calendar, since the semester's end isn't known then.
 */
fun Snapshot.classEvents(from: LocalDate): List<ClassEvent> {
    val cal = effectiveCalendar() ?: return emptyList()
    val last = listOfNotNull(cal.lastTheory, cal.lastLab).maxOrNull() ?: return emptyList()
    val week = weekOf(this)
    val out = mutableListOf<ClassEvent>()
    var d = listOfNotNull(from, cal.firstDay).max()
    val day = DateTimeFormatter.BASIC_ISO_DATE
    while (!d.isAfter(last)) {
        val wd = cal.timetableDay(d)
        if (wd != null) {
            for (slot in week[wd].orEmpty()) {
                val end = cal.endFor(slot.isLab)
                if (end != null && d.isAfter(end)) continue
                val c = slot.course
                out += ClassEvent(
                    uid = "${c.classId.ifEmpty { c.courseCode }}-${slot.meeting.slot}-${d.format(day)}@davit",
                    title = c.courseName + if (slot.isLab) " · Lab" else "",
                    location = c.venue,
                    notes = listOfNotNull(
                        listOf(c.courseCode, slot.meeting.slot, c.faculty).filter { it.isNotBlank() }.joinToString(" · "),
                        cal.dayNote(d),
                    ).joinToString("\n"),
                    date = d,
                    start = slot.meeting.start,
                    end = slot.meeting.end,
                )
            }
        }
        d = d.plusDays(1)
    }
    return out
}

/** [events] as an iCalendar file, for calendar apps that import one. Times in UTC; [stamp] dates the file. */
fun icsOf(events: List<ClassEvent>, stamp: Instant, name: String = "daVIT classes"): String {
    val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//daVIT//EN", "CALSCALE:GREGORIAN", "X-WR-CALNAME:${escape(name)}")
    for (e in events) {
        lines += listOf(
            "BEGIN:VEVENT",
            "UID:${e.uid}",
            "DTSTAMP:${utc.format(stamp)}",
            "DTSTART:${utc.format(e.startAt.toInstant())}",
            "DTEND:${utc.format(e.endAt.toInstant())}",
            "SUMMARY:${escape(e.title)}",
            "LOCATION:${escape(e.location)}",
            "DESCRIPTION:${escape(e.notes)}",
            "END:VEVENT",
        )
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { fold(it) + "\r\n" }
}

private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

/** Long lines folded at 75 bytes, as the format asks, never splitting a character. */
private fun fold(line: String): String {
    val out = StringBuilder()
    var bytes = 0
    for (ch in line) {
        val n = ch.toString().toByteArray(Charsets.UTF_8).size
        if (bytes + n > 75) {
            out.append("\r\n ")
            bytes = 1
        }
        out.append(ch)
        bytes += n
    }
    return out.toString()
}
