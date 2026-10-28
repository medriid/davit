package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Meeting
import dev.medriid.davit.vtop.SlotTimes
import dev.medriid.davit.vtop.TimetableCourse
import java.time.LocalDate
import java.time.LocalDateTime

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
    fun classesOn(d: LocalDate): List<ClassSlot> {
        val wd = if (calendar != null) calendar.timetableDay(d) else d.dayOfWeek.value
        return wd?.let { week[it] }.orEmpty()
    }
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
 * Classes of [row]'s course still to come this semester, from [at] to the last theory
 * (or lab) day. Null without a calendar, since the end date is unknown.
 */
fun Snapshot.remainingFor(row: AttendanceRow, at: LocalDateTime): Int? {
    val cal = calendar ?: return null
    val meetings = weekOf(this).values.flatten().filter { it.attendance?.key == row.key }.map { it.meeting }
    if (meetings.isEmpty()) return null
    val end = (if (isLabType(row.classType)) cal.lastLab else cal.lastTheory) ?: return null
    val today = at.toLocalDate()
    val minute = at.hour * 60 + at.minute
    var count = 0
    var d = today
    while (!d.isAfter(end)) {
        val wd = cal.timetableDay(d)
        if (wd != null) count += meetings.count { it.day == wd && (d != today || it.start > minute) }
        d = d.plusDays(1)
    }
    return count
}

/** How many of the [remaining] classes you can miss and still finish at or above 75%. Negative: out of reach. */
fun AttendanceRow.skipTillEnd(remaining: Int): Int = Math.floorDiv(4 * attended + remaining - 3 * total, 4)

/** The hint under a course: a semester budget when [remaining] is known, else the next-classes version. */
fun AttendanceRow.hint(remaining: Int?): String {
    if (remaining == null) return hint()
    val k = skipTillEnd(remaining)
    return when {
        k < 0 -> "75% can't be reached with $remaining classes left"
        atRisk -> "attend the next $mustAttend to reach 75% ($remaining left)"
        else -> "can skip ${minOf(k, remaining)} of the $remaining left"
    }
}

fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
