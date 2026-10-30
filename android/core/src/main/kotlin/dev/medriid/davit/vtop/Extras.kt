@file:UseSerializers(LocalDateSerializer::class, LocalDateTimeSerializer::class)

package dev.medriid.davit.vtop

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalDateTime

// ---- files ---------------------------------------------------------------------------

/** What a download wrote: VTOP's file name, its type and how many bytes. */
data class Download(val fileName: String, val mimeType: String, val bytes: Long)

/**
 * Proof that a person asked for a write. Create one only in the handler of a confirm
 * button the user just pressed; background work never has one, so it can't upload or apply.
 */
class UserConsent(val what: String)

/** VTOP turned a request down and said why ("Due date is over"); show [message] as it is. */
class Refused(message: String) : VtopException(message)

// ---- assignment upload ---------------------------------------------------------------

/** VTOP's upload form for one assignment, every field as rendered, minus the file. */
data class UploadForm(val classId: String, val code: String, val fields: List<Pair<String, String>>, val fileField: String)

sealed class UploadOutcome {
    /** "Uploaded successfully"; [updated] is the row's new "Last Updated On" when the page shows it. */
    data class Uploaded(val updated: String?) : UploadOutcome()

    /**
     * Replacing a file: VTOP emailed a 6-digit code to [email] (masked) and holds the new
     * file until it's entered. [error] is set when the last code was rejected.
     */
    data class NeedsOtp(
        val email: String,
        val fields: List<Pair<String, String>>,
        val otpField: String,
        val error: String? = null,
    ) : UploadOutcome()

    /** VTOP said no, in its words. */
    data class Refused(val message: String) : UploadOutcome()
}

// ---- course materials ----------------------------------------------------------------

/** One course in the course-page dropdown (this semester's and next semester's). */
@Serializable
data class CourseOption(
    /** "VL_BACSE101_00100". */
    val courseId: String,
    val semesterId: String,
    /** "ETH", "ELA", "LO", "TH"… */
    val type: String,
    val semesterName: String,
    val code: String,
    val name: String,
    /** "Embedded Theory". */
    val typeName: String,
) {
    /** Stable key for the cache: course, type and semester. */
    val key: String get() = "$semesterId|$courseId|$type"
}

/** One uploaded file in a course page. [date] is VTOP's "dd-MM-yyyy". */
@Serializable
data class Material(
    val fileId: String,
    val title: String,
    /** Module number as VTOP gives it ("5"), or empty. */
    val module: String,
    /** "Document", "Video"… without the brackets. */
    val kind: String,
    val uploaderId: String,
    val uploader: String,
    val school: String,
    val date: String,
    /** "Fall Semester 2026-27": pages also list earlier semesters' uploads. */
    val semester: String,
)

/** A course page: module titles by number ("1" → "Chemical thermodynamics and kinetics") and every material. */
@Serializable
data class CourseMaterials(val modules: Map<String, String>, val materials: List<Material>)

// ---- dashboard feeds -----------------------------------------------------------------

/** A row of the home page's course table: attendance remark, e.g. "CAT1 - Debarred / CAT2 - Debarred". */
@Serializable
data class CourseRemark(val code: String, val name: String, val type: String, val attendance: Double?, val remark: String) {
    val debarred: Boolean get() = remark.contains("debar", ignoreCase = true)
    val revoked: Boolean get() = remark.contains("revok", ignoreCase = true)
    /** Debarred or revoked: worth a banner. */
    val serious: Boolean get() = debarred || revoked
}

@Serializable
data class FeedbackItem(val title: String, val category: String, val done: Boolean)

@Serializable
data class ClubEvent(val date: LocalDate?, val title: String, val category: String, val club: String)

/** A Spot-light entry: an external [link], or a VTOP file to fetch via [download] ("downloadSpotLightViewFile/820"). */
@Serializable
data class SpotlightItem(val section: String, val title: String, val link: String?, val download: String?)

// ---- records -------------------------------------------------------------------------

/** A label/value card: proctor, HOD, dean. [role] is VTOP's heading ("Dean", "Proctor"). */
@Serializable
data class Person(val role: String, val fields: List<Pair<String, String>>) {
    fun field(vararg words: String): String =
        fields.firstOrNull { (k, _) -> words.any { k.contains(it, ignoreCase = true) } }?.second.orEmpty()

    val name: String get() = field("name")
    val email: String get() = field("mail")
    val cabin: String get() = field("cabin")
    val designation: String get() = field("designation")
}

/** A message from a class teacher or the proctor. Any part VTOP doesn't give is empty. */
@Serializable
data class VtopMessage(val from: String, val course: String, val text: String, val date: String)

/** A table read by headers, for pages whose columns vary (gate history). */
@Serializable
data class SimpleTable(val headers: List<String>, val rows: List<List<String>>)

@Serializable
data class Receipt(val invoice: String, val number: String, val date: String, val amount: Double?, val campus: String)

@Serializable
data class FeeLetter(val year: String, val term: String, val description: String, val batchId: String)

/** A registration window, e.g. Winter Semester 2026-27 from 01-Nov-2026 17:00 to 17:45. */
@Serializable
data class RegistrationWindow(
    val title: String,
    val semester: String,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
)

@Serializable
data class CurriculumCategory(val id: String, val name: String, val credits: Double?, val max: Double?)

@Serializable
data class CurriculumCourse(val group: String, val code: String, val title: String, val type: String, val credits: Double?, val ltpj: String)

/** One semester's results page: the courses and the GPA when VTOP prints one. */
@Serializable
data class SemesterGrades(val semesterId: String, val gpa: Double?, val courses: List<GradeEntry>)

// ---- hostel --------------------------------------------------------------------------

/**
 * VTOP's mess change page, read only: its heading ("Mess Booking (2024 - 25)") and what it
 * says, e.g. your mess and "YOUR BOOKING IS CONFIRMED", or "Mess Change Closed". [open]
 * is true only while the page offers a mess to pick.
 */
@Serializable
data class MessStatus(val title: String, val lines: List<String>, val open: Boolean)

// ---- hostel leave --------------------------------------------------------------------

/** The leave application form as VTOP renders it: leave types (value → label) and the proctor it goes to. */
data class LeaveForm(val proctor: String, val types: List<Pair<String, String>>, val fields: List<Pair<String, String>>)

data class LeaveRequest(
    val type: String,
    val place: String,
    val from: LocalDateTime,
    val to: LocalDateTime,
    val reason: String,
)

/** What VTOP said to a leave application. [ok] when it reads as accepted. */
data class LeaveOutcome(val ok: Boolean, val message: String)
