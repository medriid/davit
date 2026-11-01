package dev.medriid.davit.data

import org.json.JSONObject

/** Your name for the course [code] if you've given one, else [vtop]. The one place that decides. */
fun Map<String, String>.courseName(code: String, vtop: String): String {
    if (isEmpty()) return vtop
    val mine = this[code] ?: entries.firstOrNull { it.key.equals(code.trim(), ignoreCase = true) }?.value
    return mine?.trim()?.ifEmpty { null } ?: vtop
}

/**
 * A course as VTOP writes it in free text (a message's "BCSE101L - Physics"): your name if the
 * text has the code or VTOP's name of a course you've named, else as it was. [courses] are VTOP's own.
 */
fun Map<String, String>.courseIn(text: String, courses: List<Pair<String, String>>): String {
    if (isEmpty() || text.isBlank()) return text
    val named = courses.filter { (code, vtop) -> courseName(code, vtop) != vtop }.sortedByDescending { it.second.length }
    val hit = named.firstOrNull { (code, _) -> text.contains(code, ignoreCase = true) }
        ?: named.firstOrNull { (_, vtop) -> vtop.isNotBlank() && text.contains(vtop, ignoreCase = true) }
    return hit?.let { (code, vtop) -> courseName(code, vtop) } ?: text
}

/**
 * Your own names for courses, by course code ("BCSE101L" → "Python"). Theory and lab of one
 * course share the name. Applied to what the screens, widgets and notifications read; what's
 * saved from VTOP keeps VTOP's names, so clearing a name brings VTOP's back.
 */
fun Snapshot.withAliases(aliases: Map<String, String>): Snapshot {
    if (aliases.isEmpty()) return this
    fun name(code: String, vtop: String) = aliases.courseName(code, vtop)
    return copy(
        attendance = attendance.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        timetable = timetable.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        marks = marks.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        exams = exams.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        assignments = assignments.map { it.copy(courseName = name(it.courseCode, it.courseName)) },
        grades = grades?.let { g -> g.copy(entries = g.entries.map { it.copy(courseTitle = name(it.courseCode, it.courseTitle)) }) },
        feeds = feeds.copy(remarks = feeds.remarks.map { it.copy(name = name(it.code, it.name)) }),
        changes = changesWith(aliases),
    )
}

/**
 * "Since you last looked" is written at sync time with VTOP's names ("+ Physics: present · 38/51",
 * "+ Mark: Physics · Quiz 1 · 9/10"): the name at the start of each line swapped, the rest as it was.
 */
private fun Snapshot.changesWith(aliases: Map<String, String>): List<String> {
    val renames = (attendance.map { it.courseCode to it.courseName } + marks.map { it.courseCode to it.courseName } + timetable.map { it.courseCode to it.courseName })
        .distinct()
        .map { (code, vtop) -> vtop to aliases.courseName(code, vtop) }
        .filter { (vtop, mine) -> vtop.isNotEmpty() && vtop != mine }
        .sortedByDescending { it.first.length }
    if (renames.isEmpty()) return changes
    return changes.map { line ->
        val at = if (line.startsWith("+ Mark: ")) 8 else 2
        renames.firstOrNull { (vtop, _) -> line.startsWith("$vtop:", at) || line.startsWith("$vtop ·", at) }?.let { (vtop, mine) -> line.replaceRange(at, at + vtop.length, mine) } ?: line
    }
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
