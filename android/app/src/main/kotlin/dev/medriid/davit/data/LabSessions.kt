package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceDay

/**
 * VTOP's attendance table counts both periods of a lab session (L31+L32), so 28 lab
 * sessions read as 56 classes, while its day-by-day list has each session once. Where the
 * history shows exactly that, a lab row is given in sessions, as students count them and as
 * the week and "classes left" count them. Attended and total are both halved, so the
 * percentage and the 75% line are exactly as VTOP has them. Rows the history doesn't match
 * keep VTOP's numbers.
 */
fun Snapshot.withLabSessions(): Snapshot {
    if (history.isEmpty()) return this
    var changed = false
    val rows = attendance.map { row ->
        if (!isLabType(row.classType)) return@map row
        // One lab session a day; VTOP may list it as one line or one per period.
        val days = history[row.key].orEmpty().groupBy { it.date }
        val sessions = days.size
        val attendedSessions = days.values.count { lines -> lines.all { it.counts } }
        if (sessions == 0 || row.total != 2 * sessions || row.attended != 2 * attendedSessions) return@map row
        changed = true
        row.copy(attended = attendedSessions, total = sessions)
    }
    return if (changed) copy(attendance = rows) else this
}

private val AttendanceDay.counts: Boolean
    get() = status == AttendanceDay.Status.Present || status == AttendanceDay.Status.OnDuty
