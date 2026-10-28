package dev.medriid.davit.vtop

/** A term in VTOP's dropdown, e.g. id "VL20262701", name "Fall Semester 2026-27". */
data class Semester(val id: String, val name: String)

/** Logged-in session: the tokens every authenticated POST carries. */
data class VtopSession(val csrf: String, val authorizedId: String)

data class AttendanceRow(
    val courseCode: String,
    val courseName: String,
    /** As VTOP words it, e.g. "Embedded Theory", "Lab Only". */
    val classType: String,
    val attended: Int,
    val total: Int,
    /** VTOP's own figure as it prints it (may be rounded up; the app shows its own, rounded down). Null if unreadable. */
    val vtopPercent: Int?,
    /** VL... class id, from the detail link. Empty if VTOP didn't give one. */
    val classId: String,
    /** The detail link's arguments, for fetching day-by-day history. Empty if there was no link. */
    val detail: DetailKey = DetailKey(),
)

/** Arguments of VTOP's `callStudentAttendanceDetailDisplay(sem, regNo, courseId, courseType)`. */
data class DetailKey(val semesterId: String = "", val regNo: String = "", val courseId: String = "", val courseType: String = "") {
    val usable: Boolean get() = courseId.isNotEmpty()
}

/** One class in a course's attendance history. Date as VTOP prints it ("02-Sep-2026"). */
data class AttendanceDay(val date: String, val slot: String, val status: Status) {
    enum class Status { Present, Absent, OnDuty, Other }
}

data class TimetableCourse(
    val classId: String,
    val courseCode: String,
    val courseName: String,
    val courseType: String,
    val credits: Double,
    /** Slot string as registered, e.g. "A1+TA1" or "L31+L32". Empty for non-slotted courses. */
    val slot: String,
    val venue: String,
    val faculty: String,
)

/** One class meeting in the week. day: 1 = Monday. Times are minutes since midnight. */
data class Meeting(val day: Int, val start: Int, val end: Int, val slot: String)

sealed class VtopException(message: String) : Exception(message)

/** VTOP rejected the reg number or password. Don't retry: that risks a lockout. */
class BadCredentials : VtopException("VTOP rejected the registration number or password.")

/** VTOP is showing Google reCAPTCHA; only a person can get past it. */
class RecaptchaRequired : VtopException("VTOP is asking for a Google reCAPTCHA right now.")

/** The captcha wasn't solved within the attempt budget. */
class CaptchaFailed(attempts: Int) : VtopException("Couldn't solve VTOP's captcha in $attempts tries.")

/** The saved session has expired; log in again. */
class SessionExpired : VtopException("The VTOP session has expired.")

/** A page didn't look the way the parser expects (VTOP changed, or an error page). */
class UnexpectedPage(what: String) : VtopException(what)

/** This build doesn't bundle the captcha model (it isn't in the public repository). */
class ModelMissing : VtopException("This build has no captcha model, so it can't sign in. See docs/05-captcha-solver.md.")

/** Network failure. */
class Offline(cause: Throwable) : VtopException("Couldn't reach VTOP (${cause.javaClass.simpleName}).")

/** One assessment in a course's marks, e.g. "Continuous Assessment Test - 1". Null where VTOP shows "-". */
data class MarkItem(
    val title: String,
    val max: Double?,
    /** Its share of the course total, in percent points. */
    val weightage: Double?,
    val status: String,
    val scored: Double?,
    /** [scored] scaled to [weightage]. */
    val weighted: Double?,
    val remark: String,
)

data class CourseMarks(
    val classId: String,
    val courseCode: String,
    val courseName: String,
    val courseType: String,
    val items: List<MarkItem>,
)

/** One scheduled exam. Date and times are VTOP's text ("26-Nov-2026", "10:00 AM - 11:30 AM"). */
data class Exam(
    val classId: String,
    val courseCode: String,
    val courseName: String,
    /** "CAT1", "FAT"… as VTOP's group header says it. */
    val type: String,
    val date: String,
    val session: String,
    val time: String,
    val venue: String,
    val seat: String,
)

data class GradeEntry(
    val courseCode: String,
    val courseTitle: String,
    val credits: Double,
    /** Letter grade, or null while it isn't out. */
    val grade: String?,
    val examMonth: String,
)

data class GradeHistory(
    /** VTOP's own CGPA when the page shows it, else computed from [entries]; null with no graded courses. */
    val cgpa: Double?,
    val creditsEarned: Double?,
    val entries: List<GradeEntry>,
    /** Credit progress per curriculum category ("Foundation Core", "Discipline Elective"…). */
    val curriculum: List<CreditDomain> = emptyList(),
)

data class CreditDomain(val name: String, val required: Double, val earned: Double)

/** One digital assignment. Dates are VTOP's text ("15-Oct-2026"). */
data class Assignment(
    val title: String,
    val max: Double?,
    val weightage: Double?,
    val due: String,
    /** When you last uploaded; null means nothing submitted yet. */
    val updated: String?,
)

data class CourseAssignments(
    val classId: String,
    val courseCode: String,
    val courseName: String,
    val courseType: String,
    val items: List<Assignment>,
)

data class HostelInfo(val block: String, val room: String, val bed: String, val mess: String)

/** From the profile page. [photo] is VTOP's inline data URI, or null. */
data class Profile(val name: String, val gender: String, val photo: String?, val hostel: HostelInfo?)

/** A late-hour (night slip) request. */
data class NightSlip(
    val venue: String,
    val purpose: String,
    val appliedTo: String,
    val from: String,
    val to: String,
    val time: String,
    val status: String,
)

/** A hostel leave request. */
data class Leave(val place: String, val reason: String, val type: String, val from: String, val to: String, val status: String)

data class HostelRequests(
    val activeSlips: List<NightSlip> = emptyList(),
    val pastSlips: List<NightSlip> = emptyList(),
    val activeLeave: List<Leave> = emptyList(),
    val pastLeave: List<Leave> = emptyList(),
)

/** A faculty member's directory card from VTOP's employee search. */
data class FacultyInfo(
    val empId: String,
    val name: String,
    val designation: String,
    val department: String,
    val school: String,
    val email: String,
    val cabin: String,
)
