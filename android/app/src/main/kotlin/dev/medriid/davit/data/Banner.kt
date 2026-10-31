package dev.medriid.davit.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The display card: the top bar printed on a background (one of the built-in prints, or
 * your own photo), with two little stats of your choice. All yours to change; none of it
 * leaves the phone.
 */
@Serializable
data class Banner(
    /** Which built-in print, when there's no photo. */
    val style: Int = 0,
    /** Your own photo is the background (kept in the app's storage). */
    val photo: Boolean = false,
    /** Which part of a tall photo shows: 0 the top, 1 the bottom. */
    val focus: Float = 0.5f,
    val left: String = BannerStat.Exam.id,
    val right: String = BannerStat.Attendance.id,
    /** Your name in the bar instead of "daVIT". */
    val nameInBar: Boolean = false,
    /** A plain top bar instead of the card. */
    val hidden: Boolean = false,
) {
    fun toJson(): String = BannerJson.encodeToString(serializer(), this)

    companion object {
        val Styles = listOf("Waves", "Sun", "Confetti", "Blocks", "Stripes", "Halftone")

        fun fromJson(s: String?): Banner = s?.let { runCatching { BannerJson.decodeFromString(serializer(), it) }.getOrNull() } ?: Banner()
    }
}

private val BannerJson = Json { ignoreUnknownKeys = true }

/** What a corner of the display card can show. */
enum class BannerStat(val id: String, val title: String) {
    None("none", "Nothing"),
    Exam("exam", "Next exam"),
    Attendance("attendance", "Attendance"),
    AtRisk("risk", "At risk"),
    Due("due", "Due soon"),
    Reminders("reminders", "Reminders"),
    DaysLeft("days", "Teaching days left"),
    Cgpa("cgpa", "CGPA"),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: None
    }
}

/** The words for [stat] on the display card, or null when there's nothing to say. */
fun Snapshot.bannerText(stat: BannerStat, reminders: List<Reminder>, now: LocalDateTime): String? {
    val today = now.toLocalDate()
    return when (stat) {
        BannerStat.None -> null
        BannerStat.Exam -> exams
            .mapNotNull { e -> vtopDate(e.date)?.let { e to it } }
            .filter { !it.second.isBefore(today) }
            .minByOrNull { it.second }
            ?.let { (e, d) ->
                val days = ChronoUnit.DAYS.between(today, d)
                "${e.type} " + when (days) {
                    0L -> "today"
                    1L -> "tomorrow"
                    else -> "in $days days"
                }
            }
        BannerStat.Attendance -> overallPercent(attendance)?.let { "$it% overall" }
        BannerStat.AtRisk -> if (attendanceExempt) "75% rule: exempt" else "${attendance.count { it.atRisk }} at risk"
        BannerStat.Due -> assignments.flatMap { it.items }
            .count { a -> a.updated == null && vtopDate(a.due)?.let { !it.isBefore(today) && ChronoUnit.DAYS.between(today, it) <= 7 } == true }
            .let { if (it == 0) "Nothing due this week" else "$it due this week" }
        BannerStat.Reminders -> reminders.count { !it.date.isBefore(today) }
            .let { "$it reminder" + if (it == 1) "" else "s" }
        BannerStat.DaysLeft -> calendar?.instructionalDaysLeft(today)?.let { "$it teaching days left" }
        BannerStat.Cgpa -> grades?.cgpa?.let { "CGPA %.2f".format(it) }
    }
}
