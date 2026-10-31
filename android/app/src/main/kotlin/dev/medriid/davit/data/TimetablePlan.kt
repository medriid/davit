package dev.medriid.davit.data

import dev.medriid.davit.vtop.Meeting
import dev.medriid.davit.vtop.SimpleTable
import dev.medriid.davit.vtop.SlotTimes
import org.json.JSONArray
import org.json.JSONObject

/**
 * One class picked for next semester, from VTOP's course allocation: a course and one of the
 * classes allotted for it. A course with a theory and a lab part has a pick for each [type].
 */
data class PlanPick(val code: String, val title: String, val slot: String, val venue: String, val faculty: String, val type: String) {
    val meetings: List<Meeting> get() = SlotTimes.meetings(slot)
}

/** Two picks that meet at the same time on [day]. */
data class Clash(val a: PlanPick, val b: PlanPick, val day: Int, val start: Int)

/** The allotted classes in [table] (`Slot Detail | Venue | Faculty | Course Type`) as picks for [code]. */
fun allocationOptions(table: SimpleTable, code: String, title: String): List<PlanPick> {
    fun col(vararg words: String) = table.headers.indexOfFirst { h -> words.any { h.contains(it, ignoreCase = true) } }
    val slot = col("slot").takeIf { it >= 0 } ?: 0
    val venue = col("venue")
    val faculty = col("faculty")
    val type = col("type")
    return table.rows.mapNotNull { r ->
        val s = r.getOrNull(slot)?.replace(" ", "")?.uppercase().orEmpty()
        if (s.isEmpty() || s == "-") null
        else PlanPick(code, title, s, r.getOrNull(venue).orEmpty(), r.getOrNull(faculty).orEmpty(), r.getOrNull(type).orEmpty())
    }
}

/** Every pair of picks that meet at the same time, once each. */
fun List<PlanPick>.clashes(): List<Clash> = buildList {
    val list = this@clashes
    for (i in list.indices) for (j in i + 1 until list.size) {
        firstOverlap(list[i], list[j])?.let { (day, start) -> add(Clash(list[i], list[j], day, start)) }
    }
}

/** The picks in [plan] that [pick] would clash with (its own course and type aside: it replaces that). */
fun PlanPick.clashesIn(plan: List<PlanPick>): List<PlanPick> =
    plan.filter { !(it.code == code && it.type == type) && firstOverlap(this, it) != null }

private fun firstOverlap(a: PlanPick, b: PlanPick): Pair<Int, Int>? {
    for (x in a.meetings) for (y in b.meetings) {
        if (x.day == y.day && x.start < y.end && y.start < x.end) return x.day to maxOf(x.start, y.start)
    }
    return null
}

/** [plan] with [pick] in, replacing the course's earlier pick of the same type. */
fun List<PlanPick>.with(pick: PlanPick): List<PlanPick> = filterNot { it.code == pick.code && it.type == pick.type } + pick

/** The week the plan gives: day (Monday 1 …) → each meeting with its pick, in order. */
fun List<PlanPick>.week(): Map<Int, List<Pair<Meeting, PlanPick>>> =
    flatMap { p -> p.meetings.map { it to p } }.groupBy { it.first.day }.mapValues { (_, v) -> v.sortedBy { it.first.start } }.toSortedMap()

/** The plan as text, to send or keep: each course with its slot, room and faculty. */
fun List<PlanPick>.asText(semester: String): String = buildString {
    append("My timetable plan").append(if (semester.isNotBlank()) " for $semester" else "").append("\n\n")
    sortedBy { it.code }.forEach { append("${it.code} ${it.title} (${it.type}) · ${it.slot} · ${it.venue} · ${it.faculty}\n") }
}.trimEnd()

internal fun planToJson(semester: String, picks: List<PlanPick>): String = JSONObject()
    .put("semester", semester)
    .put("picks", JSONArray(picks.map {
        JSONObject().put("code", it.code).put("title", it.title).put("slot", it.slot).put("venue", it.venue).put("faculty", it.faculty).put("type", it.type)
    })).toString()

internal fun planFromJson(text: String?): Pair<String, List<PlanPick>> = runCatching {
    val o = JSONObject(text ?: return "" to emptyList())
    val a = o.getJSONArray("picks")
    o.optString("semester") to List(a.length()) { a.getJSONObject(it) }.map {
        PlanPick(it.getString("code"), it.getString("title"), it.getString("slot"), it.optString("venue"), it.optString("faculty"), it.optString("type"))
    }
}.getOrDefault("" to emptyList())

/** A plan kept under a name, to come back to and compare. */
data class SavedPlan(val id: Long, val name: String, val semester: String, val picks: List<PlanPick>)

/** "4 courses · Fri off · 8:00 on 1 day": a saved plan at a glance. */
fun List<PlanPick>.summary(): String {
    val week = week()
    val off = (1..5).filter { week[it].isNullOrEmpty() }
    val eight = week.values.count { day -> day.any { it.first.start == 8 * 60 } }
    return listOf(
        "${distinctBy { it.code }.size} course${if (distinctBy { it.code }.size == 1) "" else "s"}",
        if (off.isEmpty()) "no day off" else off.joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) } + " off",
        "8:00 on $eight day${if (eight == 1) "" else "s"}",
    ).joinToString(" · ")
}

internal fun savedPlansToJson(list: List<SavedPlan>): String = JSONArray(list.map {
    JSONObject().put("id", it.id).put("name", it.name).put("plan", planToJson(it.semester, it.picks))
}).toString()

internal fun savedPlansFromJson(text: String?): List<SavedPlan> = runCatching {
    val a = JSONArray(text ?: return emptyList())
    List(a.length()) { a.getJSONObject(it) }.map { o ->
        val (semester, picks) = planFromJson(o.getString("plan"))
        SavedPlan(o.getLong("id"), o.getString("name"), semester, picks)
    }
}.getOrDefault(emptyList())
