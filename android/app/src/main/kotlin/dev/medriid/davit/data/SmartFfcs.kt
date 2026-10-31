package dev.medriid.davit.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * FFCS's smart mode: you say which subjects you want (and, if you like, which of the allotted
 * faculty), and how you like your days; it tries every clash-free mix of slots and ranks the
 * timetables that keep your rules. Pure: VTOP's allocation comes in as [PlanPick]s.
 */

/** A subject you want next semester; [faculty] empty means any of the allotted ones. */
data class SmartSubject(val code: String, val title: String, val faculty: Set<String> = emptySet())

/** How you like your days. A null limit means you don't mind. */
data class SmartRules(
    /** Classes starting at 8:00 on at most this many days. */
    val eightAmDays: Int? = null,
    /** No class before this (minutes after midnight). */
    val earliest: Int = 8 * 60,
    /** No class still going after this. */
    val latest: Int? = null,
    /** Lunch cut to the 13:20–14:00 break on at most this many days. */
    val shortLunchDays: Int? = null,
    /** Rank timetables with whole days off higher. */
    val daysOff: Boolean = true,
    /** Rank timetables with less waiting between classes higher. */
    val fewGaps: Boolean = true,
    /** Take a course's theory and lab with the same faculty where the slots allow. */
    val sameFaculty: Boolean = true,
)

data class SmartSetup(val subjects: List<SmartSubject> = emptyList(), val rules: SmartRules = SmartRules())

/** One timetable found, and what it's like. */
data class SmartTimetable(
    val picks: List<PlanPick>,
    val daysOff: List<Int>,
    val eightAmDays: Int,
    val shortLunchDays: Int,
    val gapMinutes: Int,
    /** Courses whose theory and lab ended up with different faculty. */
    val splitFaculty: Int,
    /** The last class of the week's latest day ends at this time. */
    val latestEnd: Int,
)

sealed class SmartResult {
    /** [complete] is false when the search ran out of time: these are the best it found by then. */
    data class Found(val timetables: List<SmartTimetable>, val tried: Int, val complete: Boolean = true) : SmartResult()

    /** Nothing keeps every rule. [missing] are parts with no class left at all; [relax] the rules that, dropped alone, give timetables. */
    data class None(val missing: List<String>, val relax: List<String>) : SmartResult()
}

/** The weekdays the slot grid uses (Monday 1 … Friday 5). */
private val Weekdays = 1..5
private const val EIGHT = 8 * 60
private const val AFTERNOON = 14 * 60

/** One part of a subject (theory, lab) and the slots it can go in, each with its classes. */
private class Part(val code: String, val title: String, val type: String, val slots: List<Pair<String, List<PlanPick>>>)

/**
 * The best [limit] timetables for [subjects] under [rules], from the allotted classes in
 * [options] (course code → its classes). Tries slots, not faculty: classes in one slot meet
 * at the same times, so faculty are chosen after, by your lists and the same-faculty rule.
 */
fun findTimetables(
    subjects: List<SmartSubject>,
    options: Map<String, List<PlanPick>>,
    rules: SmartRules,
    limit: Int = 10,
    /** How long to search before settling for the best found (a phone shouldn't spin for minutes). */
    millis: Long = 3_000,
    /** Work out which rules to relax when nothing fits (off for the relaxed tries themselves). */
    explain: Boolean = true,
): SmartResult {
    val parts = mutableListOf<Part>()
    val missing = mutableListOf<String>()
    subjects.forEach { s ->
        val all = options[s.code].orEmpty()
        if (all.isEmpty()) { missing += "${s.code} has no classes in the allocation"; return@forEach }
        all.groupBy { it.type }.forEach { (type, list) ->
            val allowed = list.filter { s.faculty.isEmpty() || facultyKey(it.faculty) in s.faculty.map(::facultyKey) }
            val fits = allowed.filter { p -> p.meetings.isNotEmpty() && p.meetings.all { m -> m.start >= rules.earliest && (rules.latest == null || m.end <= rules.latest) } }
            val slots = fits.groupBy { it.slot }.toList()
            if (slots.isEmpty()) missing += "${s.code} ${type.ifBlank { "" }}".trim() + when {
                allowed.isEmpty() -> ": none with the faculty you picked"
                else -> ": none within your start and end times"
            }
            else parts += Part(s.code, s.title, type, slots)
        }
    }
    if (missing.isNotEmpty()) return SmartResult.None(missing, emptyList())

    val found = search(parts, rules, limit, millis)
    if (found.timetables.isNotEmpty()) return found

    if (!explain) return SmartResult.None(emptyList(), emptyList())
    // Nothing keeps every rule: find which ones, dropped alone, would let some through.
    fun works(s: List<SmartSubject>, r: SmartRules) = findTimetables(s, options, r, 1, millis = 800, explain = false) is SmartResult.Found
    val relax = listOfNotNull(
        "8:00 starts".takeIf { rules.eightAmDays != null && works(subjects, rules.copy(eightAmDays = null)) },
        "Short lunches".takeIf { rules.shortLunchDays != null && works(subjects, rules.copy(shortLunchDays = null)) },
        "Start time".takeIf { rules.earliest > EIGHT && works(subjects, rules.copy(earliest = EIGHT)) },
        "End time".takeIf { rules.latest != null && works(subjects, rules.copy(latest = null)) },
        "Faculty choices".takeIf { subjects.any { it.faculty.isNotEmpty() } && works(subjects.map { it.copy(faculty = emptySet()) }, rules) },
    )
    val why = when {
        relax.isNotEmpty() -> emptyList()
        !found.complete -> listOf("Couldn't find one that keeps every rule in time. Loosen a rule or take out a subject and try again.")
        else -> listOf("No mix of these subjects avoids a clash with the classes allotted")
    }
    return SmartResult.None(why, relax)
}

/** The week as 10-minute cells, 8:00 to 20:00, Monday to Friday: a slot is a bitmask over them. */
private const val CELLS_PER_DAY = 72
private const val WORDS = (5 * CELLS_PER_DAY + 63) / 64

private fun mask(meetings: List<dev.medriid.davit.vtop.Meeting>): LongArray {
    val bits = LongArray(WORDS)
    meetings.filter { it.day in Weekdays }.forEach { m ->
        val from = ((m.start - EIGHT) / 10).coerceIn(0, CELLS_PER_DAY)
        val to = ((m.end - EIGHT + 9) / 10).coerceIn(0, CELLS_PER_DAY)
        for (c in from until to) {
            val bit = (m.day - 1) * CELLS_PER_DAY + c
            bits[bit / 64] = bits[bit / 64] or (1L shl (bit % 64))
        }
    }
    return bits
}

/** A slot ready for the search: its classes, when it meets, and its cells. */
private class Choice(val slot: Pair<String, List<PlanPick>>) {
    val meetings = slot.second.first().meetings.filter { it.day in Weekdays }
    val cells = mask(meetings)
    val days = meetings.map { it.day }.distinct()
}

/**
 * Branch and bound over slots. Everything the ranking counts by the day (days off, 8:00
 * starts, short lunches) only gets worse as classes are added, so a half-made timetable that
 * already can't beat the best [limit] found is dropped with everything under it. Slots that
 * keep days free and avoid 8:00 are tried first, so good timetables turn up early. A week
 * already reached at the same step (courses' slots swapped) isn't searched twice.
 */
private fun search(parts: List<Part>, rules: SmartRules, limit: Int, millis: Long): SmartResult.Found {
    // Fewest choices first: dead ends show up early (theory before labs, which have many slots).
    val order = parts.sortedBy { it.slots.size }
    val choices = order.map { p -> p.slots.map(::Choice) }
    val chosen = arrayOfNulls<Choice>(order.size)
    val busy = LongArray(WORDS)
    // Each day's classes as (start, end), for the 8:00 and lunch counts.
    val days = Array(6) { mutableListOf<IntArray>() }
    val eight = BooleanArray(6)
    val short = BooleanArray(6)
    val best = mutableListOf<SmartTimetable>()
    // Weeks already searched at a step, each as one 64-bit hash of its cells.
    val seen = HashSet<Long>()
    val deadline = System.currentTimeMillis() + millis
    var timedOut = false
    var tried = 0
    var nodes = 0
    val budget = 5_000_000

    fun refresh(d: Int) {
        eight[d] = days[d].any { it[0] == EIGHT }
        short[d] = shortLunch(days[d].map { it[0] to it[1] })
    }
    // The best the finished timetable could still score: days as they stand (gaps and split faculty only add).
    fun bound(): Int = Weekdays.sumOf { d ->
        if (days[d].isEmpty()) (if (rules.daysOff) -400 else 0) else (if (eight[d]) 20 else 0) + (if (short[d]) 15 else 0)
    }
    fun worst(): Int = if (best.size < limit) Int.MAX_VALUE else score(best.last(), rules)

    fun go(i: Int) {
        if (++nodes > budget || timedOut) return
        if (nodes and 1023 == 0 && System.currentTimeMillis() > deadline) { timedOut = true; return }
        if (i == order.size) {
            tried++
            val t = assess(order, chosen.map { it!!.slot }, rules) ?: return
            best += t
            best.sortBy { score(it, rules) }
            if (best.size > limit) best.removeAt(best.lastIndex)
            return
        }
        // The same week at the same step leads to the same timetables.
        var h = i.toLong() * -0x61c8864680b583ebL
        for (w in busy) h = (h xor w) * -0x3d4d51c2d82b14b1L
        if (!seen.add(h)) return
        val ranked = choices[i]
            .filter { c -> c.cells.indices.none { w -> c.cells[w] and busy[w] != 0L } }
            .sortedBy { c -> c.days.count { days[it].isEmpty() } * 1000 + c.meetings.count { it.start == EIGHT } * 20 }
        for (c in ranked) {
            for (w in busy.indices) busy[w] = busy[w] or c.cells[w]
            c.meetings.forEach { days[it.day] += intArrayOf(it.start, it.end) }
            c.days.forEach(::refresh)
            val ok = (rules.eightAmDays == null || Weekdays.count { eight[it] } <= rules.eightAmDays) &&
                (rules.shortLunchDays == null || Weekdays.count { short[it] } <= rules.shortLunchDays) &&
                bound() < worst()
            if (ok) {
                chosen[i] = c
                go(i + 1)
            }
            for (w in busy.indices) busy[w] = busy[w] and c.cells[w].inv()
            c.meetings.forEach { m -> days[m.day].removeAll { it[0] == m.start && it[1] == m.end } }
            c.days.forEach(::refresh)
            if (nodes > budget || timedOut) return
        }
    }
    go(0)
    return SmartResult.Found(best.toList(), tried, complete = !timedOut && nodes <= budget)
}

/** A complete choice of slots as a timetable: faculty picked, the week measured; null if it breaks a rule. */
private fun assess(parts: List<Part>, slots: List<Pair<String, List<PlanPick>>>, rules: SmartRules): SmartTimetable? {
    // Faculty: one teaching every part of a course if the chosen slots have one in common.
    val picks = mutableListOf<PlanPick>()
    var split = 0
    parts.indices.groupBy { parts[it].code }.forEach { (_, idx) ->
        val common = if (rules.sameFaculty && idx.size > 1) {
            idx.map { i -> slots[i].second.map { facultyKey(it.faculty) }.toSet() }.reduce { a, b -> a intersect b }.firstOrNull()
        } else null
        if (idx.size > 1 && common == null) split++
        idx.forEach { i -> picks += slots[i].second.firstOrNull { common != null && facultyKey(it.faculty) == common } ?: slots[i].second.first() }
    }
    val days = Weekdays.associateWith { d -> picks.flatMap { p -> p.meetings.filter { it.day == d } }.sortedBy { it.start } }
    val eight = days.count { (_, ms) -> ms.any { it.start == EIGHT } }
    val short = days.count { (_, ms) -> shortLunch(ms.map { it.start to it.end }) }
    if (rules.shortLunchDays != null && short > rules.shortLunchDays) return null
    if (rules.eightAmDays != null && eight > rules.eightAmDays) return null
    val gaps = days.values.sumOf { ms -> ms.zipWithNext().sumOf { (a, b) -> maxOf(0, b.start - a.end).let { if (it <= 15) 0 else it } } }
    return SmartTimetable(
        picks = picks,
        daysOff = days.filter { it.value.isEmpty() }.keys.toList(),
        eightAmDays = eight,
        shortLunchDays = short,
        gapMinutes = gaps,
        splitFaculty = split,
        latestEnd = days.values.flatten().maxOfOrNull { it.end } ?: 0,
    )
}

/**
 * Lunch cut short: a class runs up to lunch (ending 12:50 or later) and the afternoon starts
 * right at 14:00, leaving only the break between.
 */
fun shortLunch(classes: List<Pair<Int, Int>>): Boolean {
    val morningEnd = classes.filter { it.second <= AFTERNOON }.maxOfOrNull { it.second } ?: return false
    val afternoonStart = classes.filter { it.first >= AFTERNOON }.minOfOrNull { it.first } ?: return false
    return morningEnd >= 12 * 60 + 50 && afternoonStart == AFTERNOON
}

/** Lower is better: days off first, then less waiting, fewer 8:00 starts and short lunches, one faculty per course. */
private fun score(t: SmartTimetable, rules: SmartRules): Int =
    (if (rules.daysOff) -t.daysOff.size * 400 else 0) +
        (if (rules.fewGaps) t.gapMinutes / 5 else 0) +
        t.eightAmDays * 20 + t.shortLunchDays * 15 +
        (if (rules.sameFaculty) t.splitFaculty * 120 else 0)

internal fun smartToJson(s: SmartSetup): String = JSONObject()
    .put("subjects", JSONArray(s.subjects.map { JSONObject().put("code", it.code).put("title", it.title).put("faculty", JSONArray(it.faculty.toList())) }))
    .put("rules", JSONObject().apply {
        s.rules.eightAmDays?.let { put("eight", it) }
        put("earliest", s.rules.earliest)
        s.rules.latest?.let { put("latest", it) }
        s.rules.shortLunchDays?.let { put("lunch", it) }
        put("off", s.rules.daysOff).put("gaps", s.rules.fewGaps).put("same", s.rules.sameFaculty)
    }).toString()

internal fun smartFromJson(text: String?): SmartSetup = runCatching {
    val o = JSONObject(text ?: return SmartSetup())
    val subs = o.getJSONArray("subjects")
    val r = o.getJSONObject("rules")
    SmartSetup(
        List(subs.length()) { subs.getJSONObject(it) }.map { j ->
            val f = j.optJSONArray("faculty")
            SmartSubject(j.getString("code"), j.optString("title"), (0 until (f?.length() ?: 0)).map { f!!.getString(it) }.toSet())
        },
        SmartRules(
            eightAmDays = if (r.has("eight")) r.getInt("eight") else null,
            earliest = r.optInt("earliest", 8 * 60),
            latest = if (r.has("latest")) r.getInt("latest") else null,
            shortLunchDays = if (r.has("lunch")) r.getInt("lunch") else null,
            daysOff = r.optBoolean("off", true),
            fewGaps = r.optBoolean("gaps", true),
            sameFaculty = r.optBoolean("same", true),
        ),
    )
}.getOrDefault(SmartSetup())
