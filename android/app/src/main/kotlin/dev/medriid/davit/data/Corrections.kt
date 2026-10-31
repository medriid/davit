package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceDay
import org.json.JSONArray
import org.json.JSONObject

/**
 * A day VTOP has you absent for that you say you attended, or were on duty for. Kept on the
 * phone so you can show your faculty the list, and so daVIT can tell you when VTOP fixes it.
 * [course] is the attendance row's key, [date] and [slot] as VTOP's day-by-day list writes them.
 */
data class Correction(val course: String, val date: String, val slot: String, val onDuty: Boolean, val at: Long) {
    val id: String get() = "$course|$date|$slot"
}

enum class CorrectionState {
    /** VTOP still has you absent. */
    Waiting,

    /** VTOP now has you present or on duty. */
    Fixed,

    /** VTOP no longer lists that class at all. */
    Gone,
}

/** Where [c] stands in [s]'s day-by-day attendance. */
fun Correction.stateIn(s: Snapshot): CorrectionState {
    val lines = s.history[course].orEmpty().filter { it.date == date && it.slot == slot }
    return when {
        lines.isEmpty() -> CorrectionState.Gone
        lines.all { it.status == AttendanceDay.Status.Present || it.status == AttendanceDay.Status.OnDuty } -> CorrectionState.Fixed
        else -> CorrectionState.Waiting
    }
}

/** The ones VTOP fixed between [old] and [new]: what to tell you about after a sync. */
fun fixedBetween(old: Snapshot?, new: Snapshot, list: List<Correction>): List<Correction> =
    if (old == null) emptyList()
    else list.filter { it.stateIn(old) == CorrectionState.Waiting && it.stateIn(new) == CorrectionState.Fixed }

/**
 * The still-open ones as a message for your faculty: course, date, slot and what you were,
 * course by course, ready to paste into an email.
 */
fun correctionsMessage(list: List<Correction>, s: Snapshot): String {
    val open = list.filter { it.stateIn(s) == CorrectionState.Waiting }
    if (open.isEmpty()) return ""
    val byCourse = open.groupBy { it.course }
    return buildString {
        append("VTOP shows me absent for these classes, but I was there:\n")
        byCourse.forEach { (key, days) ->
            val row = s.attendance.firstOrNull { it.key == key }
            append("\n").append(row?.let { "${it.courseCode} ${it.courseName} (${it.classType})" } ?: key).append("\n")
            days.sortedBy { vtopDate(it.date) }.forEach { c ->
                append("  ").append(c.date).append(" · ").append(c.slot).append(if (c.onDuty) " · on duty" else " · present").append("\n")
            }
        }
    }.trimEnd()
}

internal fun correctionsToJson(list: List<Correction>): String = JSONArray(list.map {
    JSONObject().put("course", it.course).put("date", it.date).put("slot", it.slot).put("od", it.onDuty).put("at", it.at)
}).toString()

internal fun correctionsFromJson(text: String?): List<Correction> = runCatching {
    val a = JSONArray(text ?: return emptyList())
    List(a.length()) { a.getJSONObject(it) }.map {
        Correction(it.getString("course"), it.getString("date"), it.getString("slot"), it.optBoolean("od"), it.optLong("at"))
    }
}.getOrDefault(emptyList())
