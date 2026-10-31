package dev.medriid.davit.data

import dev.medriid.davit.vtop.SlotTimes
import dev.medriid.davit.vtop.TimetableCourse
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** One class in a shared week: its slot ("A1+TA1", "L31+L32") and the block it's in ("SJT"). */
data class SharedClass(val slot: String, val block: String)

/**
 * A friend's week, from the code they showed or sent you. Only slots and blocks travel: no
 * registration number, courses or faculty. Kept encrypted on this phone, nowhere else.
 */
data class Friend(val id: Long, val name: String, val classes: List<SharedClass>)

/**
 * The code a week travels as, in a QR code or a message:
 * `DAVIT1:<name>:<slot>@<block>,<slot>@<block>…`, the name URL-encoded.
 */
object WeekCode {
    private const val PREFIX = "DAVIT1:"

    fun encode(name: String, classes: List<SharedClass>): String =
        PREFIX + URLEncoder.encode(name.trim().take(30), "UTF-8") + ":" +
            classes.distinct().joinToString(",") { "${it.slot}@${it.block}" }

    /** The name and classes in [text], which may be a whole message with the code inside; null if there's none. */
    fun decode(text: String): Pair<String, List<SharedClass>>? {
        val at = text.indexOf(PREFIX)
        if (at < 0) return null
        val body = text.substring(at + PREFIX.length).trim().substringBefore(' ').substringBefore('\n')
        val name = runCatching { URLDecoder.decode(body.substringBefore(':'), "UTF-8") }.getOrNull()?.trim().orEmpty()
        val classes = body.substringAfter(':', "").split(',').mapNotNull { c ->
            val slot = c.substringBefore('@').trim().uppercase()
            if (slot.isEmpty() || !Regex("[A-Z0-9+]+").matches(slot)) null else SharedClass(slot, c.substringAfter('@', "").trim().uppercase().take(8))
        }
        return if (name.isEmpty() && classes.isEmpty()) null else name.ifEmpty { "Friend" } to classes
    }
}

/** Your own week as it's shared: each class's slot and the block of its room. */
fun List<TimetableCourse>.shared(): List<SharedClass> = map { SharedClass(it.slot.replace(" ", "").uppercase(), blockOf(it.venue)) }.distinct()

/** "SJT-401" → "SJT", "PRP231" → "PRP", "MB-210" → "MB". */
fun blockOf(venue: String): String = venue.trim().takeWhile { it.isLetter() }.uppercase()

/** A stretch everyone is free on [day], and where each of them comes from (their class just before, if any). */
data class FreeWindow(val day: Int, val start: Int, val end: Int, val comingFrom: Map<String, String>)

/** College hours the windows are looked for in: 8:00 to 19:30. */
private const val DAY_START = 8 * 60
private const val DAY_END = 19 * 60 + 30

/**
 * When all of [people] (name → week) are out of class on [day], for at least [minutes]:
 * the gaps between everyone's classes put together, within college hours.
 */
fun freeTogether(people: Map<String, List<SharedClass>>, day: Int, minutes: Int = 30): List<FreeWindow> {
    val busy = people.mapValues { (_, classes) ->
        classes.flatMap { c -> SlotTimes.meetings(c.slot).filter { it.day == day }.map { Triple(it.start, it.end, c.block) } }
    }
    val all = busy.values.flatten().sortedBy { it.first }
    val windows = mutableListOf<Pair<Int, Int>>()
    var free = DAY_START
    all.forEach { (start, end) ->
        if (start - free >= minutes) windows += free to minOf(start, DAY_END)
        free = maxOf(free, end)
    }
    if (DAY_END - free >= minutes) windows += free to DAY_END
    return windows.filter { it.second - it.first >= minutes }.map { (s, e) ->
        FreeWindow(day, s, e, busy.mapNotNull { (who, list) -> list.filter { it.second <= s }.maxByOrNull { it.second }?.let { who to it.third } }.toMap())
    }
}

/** The weekdays anyone has classes on (Monday 1 … Saturday 6), at least Monday to Friday. */
fun teachingDays(people: Collection<List<SharedClass>>): List<Int> =
    ((1..5) + people.flatten().flatMap { c -> SlotTimes.meetings(c.slot).map { it.day } }).filter { it in 1..6 }.distinct().sorted()

internal fun friendsToJson(list: List<Friend>): String = JSONArray(list.map { f ->
    JSONObject().put("id", f.id).put("name", f.name).put("week", WeekCode.encode(f.name, f.classes))
}).toString()

internal fun friendsFromJson(text: String?): List<Friend> = runCatching {
    val a = JSONArray(text ?: return emptyList())
    List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
        WeekCode.decode(o.getString("week"))?.let { (_, classes) -> Friend(o.getLong("id"), o.getString("name"), classes) }
    }
}.getOrDefault(emptyList())
