package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow

/**
 * The courses worth watching: below 75%, below your own warning line, or a couple of
 * skips from it, by the same rule as the before-class reminders. Worst first: under the
 * line, then the lowest, then the fewest skips left.
 */
fun Snapshot.watchList(prefs: AlertPrefs): List<AttendanceRow> =
    attendance.filter(prefs::nearLine).sortedWith(compareBy({ !it.atRisk }, { it.percent }, { it.canSkipNow }))
