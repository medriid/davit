package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Meeting
import dev.medriid.davit.vtop.SlotTimes
import dev.medriid.davit.vtop.TimetableCourse
import java.time.LocalDateTime

const val THRESHOLD = 0.75

/** One class in the week, with its attendance row when we can match it. */
data class ClassSlot(val course: TimetableCourse, val meeting: Meeting, val attendance: AttendanceRow?) {
    val isLab: Boolean get() = isLabType(course.courseType) || meeting.slot.startsWith("L")
}

fun isLabType(type: String) = "lab" in type.lowercase()

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
)

fun planFor(week: Map<Int, List<ClassSlot>>, at: LocalDateTime): TodayPlan {
    val day = at.dayOfWeek.value
    val minute = at.hour * 60 + at.minute
    val today = week[day].orEmpty()
    val now = today.firstOrNull { minute >= it.meeting.start && minute < it.meeting.end }
    var next = today.firstOrNull { it.meeting.start > minute }
    var inDays = 0
    if (next == null) {
        for (d in 1..7) {
            val first = week[(day - 1 + d) % 7 + 1]?.firstOrNull()
            if (first != null) {
                next = first
                inDays = d
                break
            }
        }
    }
    return TodayPlan(today, now, next, inDays, today.count { it.meeting.end <= minute })
}

fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
