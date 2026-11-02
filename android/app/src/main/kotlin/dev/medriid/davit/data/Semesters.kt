package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.Semester

/**
 * Your current semester, from VTOP's newest-first list: the newest in which a class has been
 * held. Being listed isn't enough: once you've registered for next semester, VTOP lists it
 * with its courses and "-" for every count. A fresher before their first class has no
 * semester with classes held, so it's the oldest of the recent ones with courses.
 * [rows] reads a semester's attendance; it stops being called once the answer is known.
 */
internal inline fun currentSemester(semesters: List<Semester>, rows: (Semester) -> List<AttendanceRow>): Semester {
    var registered: Semester? = null
    for (s in semesters.take(3)) {
        val read = rows(s)
        if (read.any { it.total > 0 }) return s
        if (read.isNotEmpty()) registered = s
    }
    return registered ?: semesters.first()
}
