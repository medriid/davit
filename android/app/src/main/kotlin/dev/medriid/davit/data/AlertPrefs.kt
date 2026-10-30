package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What daVIT tells you about, and when. Nothing personal in it beyond course codes for
 * the courses you mute, so it lives in plain preferences.
 */
data class AlertPrefs(
    /** Attendance warnings at all. */
    val attendance: Boolean = true,
    /** Also warn when a course drops below this: 75 (VTOP's line), 80 or 85. */
    val warnBelow: Int = 75,
    /** Warn when only this many skips are left (0 = when none are left); -1 never. */
    val skipsLeft: Int = 0,
    /** Courses that never warn, as [courseKey]. */
    val muted: Set<String> = emptySet(),
    /** Minutes before a class to remind you; 0 is off. */
    val beforeClass: Int = 0,
    /** Before-class reminders only for courses near the line. */
    val beforeClassNearLine: Boolean = true,
    /** VTOP's attendance remarks: Debarred, Revoked. */
    val remarks: Boolean = true,
    val marks: Boolean = true,
    /** Assignments due today or tomorrow, and your own reminders. */
    val deadlines: Boolean = true,
    val exams: Boolean = true,
    /** Registration windows and VTOP notices. */
    val notices: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("attendance", attendance)
        .put("warnBelow", warnBelow)
        .put("skipsLeft", skipsLeft)
        .put("muted", JSONArray(muted.sorted()))
        .put("beforeClass", beforeClass)
        .put("beforeClassNearLine", beforeClassNearLine)
        .put("remarks", remarks)
        .put("marks", marks)
        .put("deadlines", deadlines)
        .put("exams", exams)
        .put("notices", notices)
        .toString()

    /** Is this row muted? */
    fun mutes(row: AttendanceRow) = courseKey(row.courseCode, row.classType) in muted

    /**
     * Close enough to the line that a reminder before class is worth it: below 75, below
     * your own warning line, or two skips or fewer from it.
     */
    fun nearLine(row: AttendanceRow) = row.atRisk || below(row, warnBelow) || row.canSkipNow <= maxOf(skipsLeft, 0) + 2

    companion object {
        val WarnLines = listOf(75, 80, 85)
        val SkipOptions = listOf(-1, 0, 1, 2, 3)
        val BeforeClassOptions = listOf(0, 5, 10, 15, 20)

        fun fromJson(text: String?): AlertPrefs {
            if (text.isNullOrBlank()) return AlertPrefs()
            return runCatching {
                val o = JSONObject(text)
                val d = AlertPrefs()
                AlertPrefs(
                    attendance = o.optBoolean("attendance", d.attendance),
                    warnBelow = o.optInt("warnBelow", d.warnBelow).takeIf { it in WarnLines } ?: d.warnBelow,
                    skipsLeft = o.optInt("skipsLeft", d.skipsLeft).coerceIn(-1, 3),
                    muted = o.optJSONArray("muted")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
                    beforeClass = o.optInt("beforeClass", d.beforeClass).coerceIn(0, 60),
                    beforeClassNearLine = o.optBoolean("beforeClassNearLine", d.beforeClassNearLine),
                    remarks = o.optBoolean("remarks", d.remarks),
                    marks = o.optBoolean("marks", d.marks),
                    deadlines = o.optBoolean("deadlines", d.deadlines),
                    exams = o.optBoolean("exams", d.exams),
                    notices = o.optBoolean("notices", d.notices),
                )
            }.getOrDefault(AlertPrefs())
        }
    }
}

/** Theory and lab of one course are muted apart. */
fun courseKey(code: String, type: String) = "$code|${if (isLabType(type)) "lab" else "theory"}"

/** Below [line] percent on the exact fraction: 39/52 is 75% to the integer but still below 75. */
fun below(row: AttendanceRow, line: Int) = row.total > 0 && row.attended * 100 < line * row.total
