package dev.medriid.davit.data

import dev.medriid.davit.vtop.Semester
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Public, non-personal data that VTOP doesn't serve in a usable form: the academic
 * calendar (IshanJ25/vtop-cal-data) and the faculty directory (IshanJ25/vtop-fac-data).
 * Plain GETs of public files; nothing about you is sent. Blocking: call off the main thread.
 */
object PublicData {
    private const val CAL = "https://raw.githubusercontent.com/IshanJ25/vtop-cal-data/refs/heads/main/"
    private const val FACULTY = "https://raw.githubusercontent.com/IshanJ25/vtop-fac-data/refs/heads/main/data/vellore.json"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun get(url: String): String? = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (it.isSuccessful) it.body?.string() else null
    }

    /** `curr_sem.txt`: key=value lines (year, sem, id, …). */
    fun currentSemester(): Map<String, String> =
        get(CAL + "curr_sem.txt").orEmpty().lines()
            .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()

    /**
     * The calendar for [semester]. The published files are `calendars/<year>/<Fall|Winter|…>.json`;
     * the year comes from curr_sem.txt when it's this semester, else from the name
     * ("Fall Semester 2026-27" → 2026; Winter and Summer may sit under either year, so both are tried).
     */
    fun calendar(semester: Semester): AcademicCalendar? {
        val current = runCatching { currentSemester() }.getOrDefault(emptyMap())
        val season = if (current["id"] == semester.id) current["sem"] else semester.name.trim().substringBefore(' ')
        season ?: return null
        val years = if (current["id"] == semester.id) listOfNotNull(current["year"]) else {
            val first = Regex("""(20\d\d)""").find(semester.name)?.value?.toInt() ?: return null
            if (season.equals("Fall", ignoreCase = true)) listOf("$first") else listOf("${first + 1}", "$first")
        }
        for (y in years) {
            val text = runCatching { get("${CAL}calendars/$y/$season.json") }.getOrNull() ?: continue
            return AcademicCalendar.fromPublished(semester.id, text)
        }
        return null
    }

    /** Faculty name (normalised by [facultyKey]) → employee id. */
    fun facultyDirectory(): Map<String, String> {
        val text = get(FACULTY) ?: return emptyMap()
        val a = JSONObject(text).getJSONArray("data")
        return List(a.length()) { a.getJSONObject(it) }.associate { facultyKey(it.getString("name")) to it.getString("emp_id") }
    }
}

/** "Dr. Asha  Raman" and "ASHA RAMAN" match: uppercase, titles and punctuation dropped. */
fun facultyKey(name: String): String =
    name.uppercase().replace(Regex("""^(DR|PROF|MR|MRS|MS)\.?\s+"""), "").replace(Regex("[^A-Z ]"), " ").trim().replace(Regex("\\s+"), " ")
