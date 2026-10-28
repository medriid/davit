package dev.medriid.davit.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Something you added yourself, due on [date]. Kept encrypted on the phone with the rest. */
data class Reminder(val id: Long, val title: String, val date: LocalDate)

internal fun List<Reminder>.toJson(): String = JSONArray(map {
    JSONObject().put("id", it.id).put("title", it.title).put("date", it.date.toString())
}).toString()

internal fun remindersFromJson(text: String): List<Reminder> = runCatching {
    val a = JSONArray(text)
    List(a.length()) { a.getJSONObject(it) }.map { Reminder(it.getLong("id"), it.getString("title"), LocalDate.parse(it.getString("date"))) }
}.getOrDefault(emptyList())
