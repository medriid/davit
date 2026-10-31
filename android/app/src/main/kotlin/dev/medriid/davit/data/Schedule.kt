package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Meeting
import dev.medriid.davit.vtop.SlotTimes
import dev.medriid.davit.vtop.TimetableCourse
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

const val THRESHOLD = 0.75

/** One class in the week, with its attendance row when we can match it. */
data class ClassSlot(val course: TimetableCourse, val meeting: Meeting, val attendance: AttendanceRow?) {
    val isLab: Boolean get() = isLabType(course.courseType) || meeting.slot.startsWith("L")
}

fun isLabType(type: String) = "lab" in type.lowercase()

/** Identifies a course's attendance row across syncs: theory and lab of one course differ. */
val AttendanceRow.key: String get() = "$courseCode|$classType"

/*
 * The 75% rule (with CGPA under 9) is on the exact fraction: 30/40 is in, 74.5% is out
 * even though VTOP may print it as 75%. So the shown percentage rounds *down* (a "75%"
 * on screen always means you're in), and every check below is integer arithmetic on
 * attended/total, with 75% as 4·attended ≥ 3·total, so no floating-point edge can
 * tip a borderline course either way.
 */

/** attended/total as a whole percentage, rounded down. */
val AttendanceRow.percent: Int
    get() = if (total == 0) 100 else attended * 100 / total

/**
 * The mean of every course's exact attended/total, rounded down once at the end; each
 * course counts the same however many classes it has. Exact (a common denominator) so
 * the floor can't slip; null when no course has held a class.
 */
fun overallPercent(rows: List<AttendanceRow>): Int? {
    val held = rows.filter { it.total > 0 }
    if (held.isEmpty()) return null
    var num = BigInteger.ZERO
    var den = BigInteger.ONE
    for (r in held) {
        val t = r.total.toBigInteger()
        num = num * t + r.attended.toBigInteger() * den
        den *= t
    }
    return (num * 100.toBigInteger() / (den * held.size.toBigInteger())).toInt()
}

val AttendanceRow.atRisk: Boolean get() = 4 * attended < 3 * total

/** Classes you can miss in a row from now and still sit at or above 75%. */
val AttendanceRow.canSkipNow: Int
    get() = ((4 * attended - 3 * total) / 3).coerceAtLeast(0)

/** Classes to attend in a row to climb back to 75%. */
val AttendanceRow.mustAttend: Int
    get() = (3 * total - 4 * attended).coerceAtLeast(0)

/** The percentage (rounded down) after missing the next [n] classes. */
fun AttendanceRow.afterMissing(n: Int): Int =
    if (total + n == 0) 100 else attended * 100 / (total + n)

fun AttendanceRow.hint(): String =
    if (atRisk) "attend the next $mustAttend to reach 75%"
    else "can skip $canSkipNow and stay at 75%"

/** Matches a timetable course to its attendance row: class id first, then code + theory/lab. */
fun matchAttendance(course: TimetableCourse, rows: List<AttendanceRow>): AttendanceRow? =
    rows.firstOrNull { it.classId.isNotEmpty() && it.classId == course.classId }
        ?: rows.firstOrNull { it.courseCode == course.courseCode && isLabType(it.classType) == isLabType(course.courseType) }
        ?: rows.firstOrNull { it.courseCode == course.courseCode }

/** Every class of the week, by weekday (1 = Monday), in time order. */
fun weekOf(snapshot: Snapshot): Map<Int, List<ClassSlot>> =
    snapshot.timetable
        .flatMap { course ->
            val att = matchAttendance(course, snapshot.attendance)
            SlotTimes.meetings(course.slot).map { ClassSlot(course, it, att) }
        }
        .groupBy { it.meeting.day }
        .mapValues { (_, v) -> v.sortedBy { it.meeting.start } }

data class TodayPlan(
    val today: List<ClassSlot>,
    val now: ClassSlot?,
    /** The next class, today or on a later day. */
    val next: ClassSlot?,
    /** Days from today to [next] (0 = today). */
    val nextInDays: Int,
    val done: Int,
    /** The date [next] falls on (its weekday can differ from the slot's when a day order swaps it). */
    val nextDate: LocalDate? = null,
    /** Today's calendar entry, when the calendar knows it (a holiday, an exam day…). */
    val calendarDay: CalDay? = null,
)

/**
 * Now and next. With a [calendar], holidays and exam days have no classes and a
 * substituted day (a Saturday running Monday's order) uses that weekday's timetable.
 */
fun planFor(week: Map<Int, List<ClassSlot>>, at: LocalDateTime, calendar: AcademicCalendar? = null): TodayPlan {
    val date = at.toLocalDate()
    fun classesOn(d: LocalDate) = classesOn(week, d, calendar)
    val minute = at.hour * 60 + at.minute
    val today = classesOn(date)
    val now = today.firstOrNull { minute >= it.meeting.start && minute < it.meeting.end }
    var next = today.firstOrNull { it.meeting.start > minute }
    var inDays = 0
    if (next == null) {
        for (d in 1..21) {
            val first = classesOn(date.plusDays(d.toLong())).firstOrNull()
            if (first != null) {
                next = first
                inDays = d
                break
            }
        }
    }
    return TodayPlan(
        today, now, next, inDays, today.count { it.meeting.end <= minute },
        next?.let { date.plusDays(inDays.toLong()) }, calendar?.day(date),
    )
}

/**
 * The classes on [date]: the timetable of the weekday the calendar says it runs (a
 * Saturday on Wednesday's order gets Wednesday's classes); none on holidays and exam days.
 */
fun classesOn(week: Map<Int, List<ClassSlot>>, date: LocalDate, calendar: AcademicCalendar?): List<ClassSlot> {
    val wd = if (calendar != null) calendar.timetableDay(date) else date.dayOfWeek.value
    return wd?.let { week[it] }.orEmpty()
}

/** The dates the Week tab shows: Monday to Sunday of this week, or of the coming one on a Sunday. */
fun weekDates(today: LocalDate): List<LocalDate> {
    val monday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.plusDays(1) else today.with(DayOfWeek.MONDAY)
    return (0..6).map { monday.plusDays(it.toLong()) }
}

/**
 * What the calendar changes about [date], in a few words ("Runs Wednesday's timetable",
 * "Holiday: Deepavali", "CAT-2: no regular classes"); null for an ordinary day.
 */
fun AcademicCalendar.dayNote(date: LocalDate): String? {
    val d = day(date) ?: return null
    return when {
        d.instructional -> d.dayOrder.takeIf { it in 1..7 && it != date.dayOfWeek.value }
            ?.let { "Runs ${DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH)}'s timetable" }
        d.exam -> "${d.type}: no regular classes"
        d.type.equals("Holiday", ignoreCase = true) -> d.note?.let { "Holiday: $it" }
        d.type.equals("None", ignoreCase = true) -> null
        else -> d.note ?: d.type
    }
}

/**
 * Which weekday's timetable each past date actually followed, read off your attendance
 * history: a class in slot A1 (which meets on Monday) on a Saturday means that Saturday
 * ran Monday's order. Only dates whose slots agree on one weekday are kept.
 */
fun Snapshot.observedDayOrders(): Map<LocalDate, Int> =
    history.values.flatten()
        .mapNotNull { d ->
            // VTOP's Day/Time column says the order outright; the slot is the fallback.
            val days = d.dayOrder?.let { setOf(it) } ?: SlotTimes.meetings(d.slot).map { m -> m.day }.toSet()
            vtopDate(d.date)?.let { it to days }
        }
        .filter { (_, days) -> days.isNotEmpty() }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (date, sets) ->
            // Every class that day must fit one weekday; a lab pair and a theory slot can both point to it.
            sets.reduce { a, b -> a intersect b }.singleOrNull()?.let { date to it }
        }
        .toMap()

/** The calendar, corrected wherever your attendance shows a past day followed another order. */
fun Snapshot.effectiveCalendar(): AcademicCalendar? {
    val cal = calendar ?: return null
    val seen = observedDayOrders()
    if (seen.isEmpty()) return cal
    return cal.copy(days = cal.days.map { d ->
        val actual = seen[d.date] ?: return@map d
        if (d.instructional && d.dayOrder == actual) d else d.copy(type = "Instructional", dayOrder = actual)
    })
}

/**
 * Classes of [row]'s course still to come this semester, from [at] to the last theory
 * (or lab) day. Null without a calendar, since the end date is unknown.
 */
fun Snapshot.remainingFor(row: AttendanceRow, at: LocalDateTime): Int? {
    val cal = effectiveCalendar() ?: return null
    val meetings = meetingsFor(row)
    if (meetings.isEmpty()) return null
    val end = cal.endFor(isLabType(row.classType)) ?: return null
    var count = 0
    var d = at.toLocalDate()
    while (!d.isAfter(end)) {
        count += countOn(meetings, cal, d, at)
        d = d.plusDays(1)
    }
    return count
}

/** The weekly meetings of [row]'s course (a lab session is one meeting, as VTOP counts it). */
fun Snapshot.meetingsFor(row: AttendanceRow, week: Map<Int, List<ClassSlot>> = weekOf(this)): List<Meeting> =
    week.values.flatten().filter { it.attendance?.key == row.key }.map { it.meeting }

/** The last teaching day for labs, or for theory. */
fun AcademicCalendar.endFor(lab: Boolean): LocalDate? = if (lab) lastLab else lastTheory

/**
 * How many of [meetings] are held on [d]: by the calendar's day order (none on a holiday
 * or exam day), or the date's own weekday without a calendar. On [at]'s day, only the
 * ones that haven't started yet.
 */
fun countOn(meetings: List<Meeting>, cal: AcademicCalendar?, d: LocalDate, at: LocalDateTime): Int {
    val wd = (if (cal != null) cal.timetableDay(d) else d.dayOfWeek.value) ?: return 0
    val minute = at.hour * 60 + at.minute
    return meetings.count { it.day == wd && (d != at.toLocalDate() || it.start > minute) }
}

/** How many of the [remaining] classes you can miss and still finish at or above 75%. Negative: out of reach. */
fun AttendanceRow.skipTillEnd(remaining: Int): Int = Math.floorDiv(4 * attended + remaining - 3 * total, 4)

/** The hint under a course: a semester budget when [remaining] is known, else the next-classes version. */
fun AttendanceRow.hint(remaining: Int?): String {
    if (remaining == null) return hint()
    val k = skipTillEnd(remaining)
    return when {
        remaining == 0 -> if (atRisk) "no classes left to reach 75%" else "no classes left"
        k < 0 -> "75% can't be reached with $remaining classes left"
        atRisk -> "attend the next $mustAttend to reach 75% ($remaining left)"
        k >= remaining -> if (remaining == 1) "can skip the last one" else "can skip all $remaining left"
        else -> "can skip $k of the $remaining left"
    }
}

fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

/** "Dr. Asha  Raman" and "ASHA RAMAN" match: uppercase, titles and punctuation dropped. */
fun facultyKey(name: String): String =
    name.uppercase().replace(Regex("""^(DR|PROF|MR|MRS|MS)\.?\s+"""), "").replace(Regex("[^A-Z ]"), " ").trim().replace(Regex("\\s+"), " ")
