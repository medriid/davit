package dev.medriid.davit.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** One day of the academic calendar. [type] as published: Instructional, Holiday, CAT-1, CAT-2, FAT… */
data class CalDay(val date: LocalDate, val type: String, val dayOrder: Int, val note: String?) {
    val instructional: Boolean get() = type.equals("Instructional", ignoreCase = true)
    val exam: Boolean get() = type.startsWith("CAT", ignoreCase = true) || type.equals("FAT", ignoreCase = true)
}

/**
 * The semester's calendar: which days have classes, and which weekday's timetable each
 * one follows (a Saturday can run Monday's). Days outside it fall back to their own weekday.
 */
data class AcademicCalendar(
    val semesterId: String,
    val firstDay: LocalDate?,
    val lastTheory: LocalDate?,
    val lastLab: LocalDate?,
    val days: List<CalDay>,
) {
    private val byDate: Map<LocalDate, CalDay> by lazy { days.associateBy { it.date } }

    fun day(date: LocalDate): CalDay? = byDate[date]

    /** The weekday (1 = Monday) whose timetable runs on [date], or null when there are no regular classes. */
    fun timetableDay(date: LocalDate): Int? {
        val d = byDate[date] ?: return date.dayOfWeek.value
        if (!d.instructional) return null
        return d.dayOrder.takeIf { it in 1..7 } ?: date.dayOfWeek.value
    }

    /** Instructional days from [from] (inclusive) to the last theory day. */
    fun instructionalDaysLeft(from: LocalDate): Int =
        days.count { it.instructional && !it.date.isBefore(from) && (lastTheory == null || !it.date.isAfter(lastTheory)) }

    /** Contiguous runs of the same exam type: "CAT-1 7–15 Sep". */
    fun examWindows(): List<Triple<String, LocalDate, LocalDate>> {
        val out = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        for (d in days.filter { it.exam }.sortedBy { it.date }) {
            val last = out.lastOrNull()
            if (last != null && last.first == d.type && !d.date.isAfter(last.third.plusDays(3))) {
                out[out.lastIndex] = last.copy(third = d.date)
            } else {
                out += Triple(d.type, d.date, d.date)
            }
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sem", semesterId)
        .put("first", firstDay?.toString() ?: "").put("lastTheory", lastTheory?.toString() ?: "").put("lastLab", lastLab?.toString() ?: "")
        .put("days", JSONArray(days.map { JSONArray().put(it.date.toString()).put(it.type).put(it.dayOrder).put(it.note ?: "") }))

    companion object {
        private fun date(s: String?): LocalDate? = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        fun fromJson(o: JSONObject): AcademicCalendar {
            val a = o.getJSONArray("days")
            return AcademicCalendar(
                o.getString("sem"), date(o.optString("first")), date(o.optString("lastTheory")), date(o.optString("lastLab")),
                List(a.length()) { a.getJSONArray(it) }.map {
                    CalDay(LocalDate.parse(it.getString(0)), it.getString(1), it.getInt(2), it.getString(3).ifEmpty { null })
                },
            )
        }

        /** The published format: `{metadata: {...}, data: {"2026-07": [{date, type, dayOrder, note}, ...]}}`. */
        fun fromPublished(semesterId: String, text: String): AcademicCalendar {
            val o = JSONObject(text)
            val meta = o.optJSONObject("metadata") ?: JSONObject()
            val data = o.getJSONObject("data")
            val days = data.keys().asSequence().flatMap { month ->
                val a = data.getJSONArray(month)
                List(a.length()) { a.getJSONObject(it) }.asSequence()
            }.map {
                CalDay(
                    LocalDate.parse(it.getString("date")), it.getString("type"), it.optInt("dayOrder"),
                    if (it.isNull("note")) null else it.optString("note").ifBlank { null },
                )
            }.sortedBy { it.date }.toList()
            return AcademicCalendar(
                semesterId, date(meta.optString("firstInstructionalDay")),
                date(meta.optString("lastTheoryInstructionalDay")), date(meta.optString("lastLabInstructionalDay")), days,
            )
        }
    }
}
