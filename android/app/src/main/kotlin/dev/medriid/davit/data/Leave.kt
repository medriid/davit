package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import java.time.LocalDate
import java.time.LocalDateTime

/** Where a course stands after a planned leave. */
enum class LeaveVerdict {
    /** Still at or above 75% right after. */
    Safe,

    /** Below 75% after, but there are enough classes left to climb back. */
    Recoverable,

    /** Below 75% after, and not enough classes left this semester to get back. */
    OutOfReach,

    /** CGPA 9 or more: the 75% rule doesn't apply. */
    Exempt,
}

/**
 * One course after the leave: [missed] of its classes fall on the days you picked, the
 * [between] ones before your last leave day are taken as attended, and [after] is the
 * row as it would stand then. [left] is how many of its classes remain after that.
 */
data class LeaveImpact(
    val row: AttendanceRow,
    val missed: Int,
    val between: Int,
    val after: AttendanceRow,
    val left: Int?,
    val verdict: LeaveVerdict,
    /** False when the course has no slot in the timetable, so its classes can't be counted. */
    val inTimetable: Boolean,
)

/**
 * Every course after missing [dates] (past ones are ignored: VTOP already has them),
 * assuming every other class until the last of them is attended. Day orders, holidays,
 * exam days and each course's last teaching day come from the calendar, and the 75%
 * line is checked on the exact fraction, never a rounded percentage.
 */
fun Snapshot.afterLeave(dates: Set<LocalDate>, at: LocalDateTime): List<LeaveImpact> {
    val week = weekOf(this)
    val cal = effectiveCalendar()
    val today = at.toLocalDate()
    val chosen = dates.filter { !it.isBefore(today) }.toSet()
    val last = chosen.maxOrNull()
    return attendance.map { row ->
        val meetings = meetingsFor(row, week)
        val end = cal?.endFor(isLabType(row.classType))
        fun counted(d: LocalDate) = if (end != null && d.isAfter(end)) 0 else countOn(meetings, cal, d, at)
        val missed = chosen.sumOf(::counted)
        var between = 0
        if (last != null) {
            var d = today
            while (!d.isAfter(last)) {
                if (d !in chosen) between += counted(d)
                d = d.plusDays(1)
            }
        }
        val after = row.copy(attended = row.attended + between, total = row.total + between + missed)
        val left = remainingFor(row, at)?.let { (it - missed - between).coerceAtLeast(0) }
        val verdict = when {
            attendanceExempt -> LeaveVerdict.Exempt
            !after.atRisk -> LeaveVerdict.Safe
            left == null || after.skipTillEnd(left) >= 0 -> LeaveVerdict.Recoverable
            else -> LeaveVerdict.OutOfReach
        }
        LeaveImpact(row, missed, between, after, left, verdict, meetings.isNotEmpty())
    }.sortedWith(compareBy({ it.missed == 0 }, { it.verdict == LeaveVerdict.Safe }, { it.after.percent }))
}
