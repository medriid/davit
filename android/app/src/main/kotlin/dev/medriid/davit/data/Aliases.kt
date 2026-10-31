package dev.medriid.davit.data

import org.json.JSONObject

/**
 * Your own names for courses, by course code ("BCSE101L" → "Python"). Theory and lab of one
 * course share the name. Applied to what the screens, widgets and notifications read; what's
 * saved from VTOP keeps VTOP's names, so clearing a name brings VTOP's back.
 */
fun Snapshot.withAliases(aliases: Map<String, String>): Snapshot {
    if (aliases.isEmpty()) return this
    fun name(code: String, vtop: String) = aliases[code]?.trim()?.ifEmpty { null } ?: vtop
    return copy(
        attendance = attendance.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        timetable = timetable.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        marks = marks.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        exams = exams.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        assignments = assignments.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        grades = grades?.let { g -> g.copy(entries = g.entries.map { it.copy(courseTitle = name(it.courseCode, it.courseTitle)) }) },
        feeds = feeds.copy(remarks = feeds.remarks.map { it.copy(name = name(it.code, it.name)) }),
    )
}

/** Every course this semester once, by code, with VTOP's own name: what the names screen lists. */
fun Snapshot.coursesForAliases(): List<Pair<String, String>> =
    (timetable.map { it.courseCode to it.courseName } + attendance.map { it.courseCode to it.courseName })
        .filter { it.first.isNotBlank() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }

internal fun aliasesToJson(a: Map<String, String>): String = JSONObject(a.filterValues { it.isNotBlank() }).toString()

internal fun aliasesFromJson(s: String?): Map<String, String> = runCatching {
    val o = JSONObject(s ?: return emptyMap())
    o.keys().asSequence().associateWith { o.getString(it) }
}.getOrDefault(emptyMap())
