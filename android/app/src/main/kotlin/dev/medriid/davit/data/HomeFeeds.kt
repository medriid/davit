package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.ClassGroup
import dev.medriid.davit.vtop.Circular
import dev.medriid.davit.vtop.ClubEvent
import dev.medriid.davit.vtop.CourseRemark
import dev.medriid.davit.vtop.FacultyProject
import dev.medriid.davit.vtop.FeedbackItem
import dev.medriid.davit.vtop.RegistrationWindow
import dev.medriid.davit.vtop.VtopWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What the VTOP home page says beside the data: attendance remarks (debarment), pending
 * feedback, club events, the proctor's note and the registration window. Remarks come
 * with every sync; the rest about daily.
 */
@Serializable
data class HomeFeeds(
    val remarks: List<CourseRemark> = emptyList(),
    val remarksAt: Long = 0,
    val feedback: List<FeedbackItem> = emptyList(),
    val events: List<ClubEvent> = emptyList(),
    val proctorNote: String? = null,
    val registration: List<RegistrationWindow> = emptyList(),
    val dailyAt: Long = 0,
    /** VTOP's calendar class groups for this semester, for choosing one by hand. */
    val classGroups: List<ClassGroup> = emptyList(),
    /** Circulars, faculty projects and what's open for registration, read about once a day to alert you. */
    val circulars: List<Circular> = emptyList(),
    val projects: List<FacultyProject> = emptyList(),
    val windows: List<VtopWindow> = emptyList(),
    val watchAt: Long = 0,
    /** The home page's upcoming-assignments list when assignments were last read in full. */
    val assignmentSig: String = "",
    val assignmentsAt: Long = 0,
    /** When the timetable and grades were last read: they rarely change, so most syncs skip them. */
    val timetableAt: Long = 0,
    val gradesAt: Long = 0,
) {
    val pendingFeedback: List<FeedbackItem> get() = feedback.filter { !it.done }

    /** The window that's on now or next, if it hasn't ended. */
    fun nextRegistration(now: LocalDateTime): RegistrationWindow? =
        registration.filter { (it.end ?: it.start)?.isAfter(now) == true }.minByOrNull { it.start ?: it.end!! }

    /**
     * A new reading of what's watched. Null is a read that failed and an empty list a page that
     * changed shape: either way the last good list stays, so a later reading doesn't count
     * everything as new. Windows merge by id, as one page can fail alone.
     */
    fun withWatched(circulars: List<Circular>?, projects: List<FacultyProject>?, windows: List<VtopWindow>?, now: Long): HomeFeeds {
        val merged = (windows.orEmpty() + this.windows.filter { o -> windows.orEmpty().none { it.id == o.id } })
        return copy(
            circulars = circulars?.ifEmpty { null } ?: this.circulars,
            projects = projects?.ifEmpty { null } ?: this.projects,
            windows = merged,
            watchAt = if (circulars != null && projects != null && windows != null) now else watchAt,
        )
    }

    fun eventsOn(day: LocalDate): List<ClubEvent> = events.filter { it.date == day }

    fun toJson(): String = CacheJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(text: String): HomeFeeds = runCatching { CacheJson.decodeFromString(serializer(), text) }.getOrDefault(HomeFeeds())
    }
}

/** The JSON settings every new cache uses: tolerant of fields added or dropped between versions. */
internal val CacheJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * The home page's remark for an attendance row. The feed names types by VTOP's short
 * codes (ETH, ELA, LO, TH) while attendance says "Embedded Theory", so the match is on
 * the course code plus lab or theory; a single row for the code matches whatever its type.
 */
fun HomeFeeds.remarkFor(row: AttendanceRow): CourseRemark? {
    val same = remarks.filter { it.code.equals(row.courseCode, ignoreCase = true) }
    if (same.size <= 1) return same.firstOrNull()
    return same.firstOrNull { isLabCode(it.type) == isLabType(row.classType) } ?: same.first()
}

/** ELA, LO and friends are labs; ETH, TH, SS, EPJ are not. */
fun isLabCode(type: String): Boolean {
    val t = type.uppercase()
    return t == "LO" || t == "ELA" || t == "LAB" || t.endsWith("LA")
}

