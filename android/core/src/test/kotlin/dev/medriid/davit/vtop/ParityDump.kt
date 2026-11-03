package dev.medriid.davit.vtop

import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Writes what the parsers read from each fixture to `desktop/src-tauri/tests/parity.json`.
 * The desktop app's Rust parsers are tested against that file, so the two stay in step:
 * run this (`gradlew :core:test --tests '*ParityDump*'`) after changing a parser.
 */
class ParityDump {
    private fun html(name: String) = javaClass.getResource("/html/$name")!!.readText()

    private val cases: List<Triple<String, String, (String) -> Any?>> = listOf(
        Triple("semesters", "attendance.html", Parsers::semesters),
        Triple("attendance", "attendance.html", Parsers::attendance),
        Triple("attendance_detail", "attendance_detail.html", Parsers::attendanceDetail),
        Triple("timetable", "timetable.html", Parsers::timetable),
        Triple("timetable", "timetable_registered.html", Parsers::timetable),
        Triple("marks", "marks.html", Parsers::marks),
        Triple("exams", "exams.html", Parsers::exams),
        Triple("assignment_courses", "assignments.html", Parsers::assignmentCourses),
        Triple("assignments", "assignment_detail.html", Parsers::assignments),
        Triple("assignments", "assignment_detail_buttons.html", Parsers::assignments),
        Triple("profile", "profile.html", Parsers::profile),
        Triple("grade_history", "grades.html", Parsers::gradeHistory),
        Triple("faculty_search", "faculty_search.html", Parsers::facultySearch),
        Triple("faculty_search", "faculty_search_none.html", Parsers::facultySearch),
        Triple("calendar_months", "calendar_oct.html", Parsers::calendarMonths),
        Triple("night_slips", "hostel_requests.html", Parsers::nightSlips),
        Triple("remarks", "dash_courses.html", Feeds::remarks),
        Triple("feedbacks", "dash_feedback.html", Feeds::feedbacks),
        Triple("events", "dash_events.html", Feeds::events),
        Triple("spotlight", "content_spotlight.html", Feeds::spotlight),
        Triple("proctor", "proctor.html", Feeds::proctor),
        Triple("hod_dean", "hod_dean.html", Feeds::hodDean),
        Triple("messages", "messages_empty.html", Feeds::messages),
        Triple("messages", "messages_table.html", Feeds::messages),
        Triple("table", "biometric.html", Feeds::table),
        Triple("table", "qcm.html", Feeds::table),
        Triple("table", "allocation_classes.html", Feeds::table),
        Triple("receipts", "receipts.html", Feeds::receipts),
        Triple("fee_letters", "fee_letters.html", Feeds::feeLetters),
        Triple("library", "library.html", Feeds::libraryDues),
        Triple("registration", "registration.html", Feeds::registration),
        Triple("curriculum_categories", "curriculum.html", Feeds::curriculumCategories),
        Triple("curriculum_courses", "curriculum_category.html", Feeds::curriculumCourses),
        Triple("mess", "mess_change.html", Feeds::messChange),
        Triple("mess", "mess_change_closed.html", Feeds::messChange),
        Triple("courses", "course_pages.html", CoursePages::courses),
        Triple("materials", "course_detail.html", CoursePages::materials),
        Triple("clubs", "club_events.html", Clubs::page),
        Triple("general_certificates", "general_certificates.html", Clubs::generalCertificates),
        Triple("class_feedback", "course_feedback_list.html", Clubs::classFeedback),
        Triple("paper_seeing", "paper_seeing_courses.html", PaperSeeing::page),
        Triple("paper_seeing", "paper_seeing_none.html", PaperSeeing::page),
        Triple("leave_form", "leave_form.html", Feeds::leaveForm),
        Triple("form_read", "night_slip_form.html", { h -> Forms.read(h) }),
        Triple("form_read", "night_slip_pending.html", { h -> Forms.read(h) }),
        Triple("reply", "upload_done.html", Forms::reply),
        Triple("reply", "night_slip_pending.html", Forms::reply),
        Triple("upload_outcome", "upload_done.html", { h -> CoursePages.uploadOutcome(h, "NONE") }),
        Triple("upload_outcome", "upload_otp.html", { h -> CoursePages.uploadOutcome(h, "NONE") }),
        Triple("upload_outcome", "upload_otp_wrong.html", { h -> CoursePages.uploadOutcome(h, "NONE") }),
        Triple("upload_outcome", "upload_closed.html", { h -> CoursePages.uploadOutcome(h, "NONE") }),
        Triple("upload_form", "upload_form.html", { h -> CoursePages.uploadForm(h, "C1", "D1") }),
    )

    @Test
    fun dump() {
        val target = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "desktop/src-tauri/tests") }.firstOrNull { it.isDirectory } ?: return
        val entries = cases.map { (parser, fixture, read) ->
            """{"parser":${json(parser)},"fixture":${json(fixture)},"out":${json(read(html(fixture)))}}"""
        } + listOf(
            """{"parser":"calendar_month","fixture":"calendar_oct.html","out":${json(Parsers.calendarMonth(html("calendar_oct.html"), "01-OCT-2026"))}}""",
            """{"parser":"semester_grades","fixture":"grade_view.html","out":${json(Feeds.semesterGrades(html("grade_view.html"), "VL1"))}}""",
            """{"parser":"faculty","fixture":"faculty.html","out":${json(Parsers.faculty(html("faculty.html"), "E1"))}}""",
            """{"parser":"leaves_applied","fixture":"hostel_requests.html","out":${json(Parsers.leaves(html("hostel_requests.html"), "LeaveAppliedTable"))}}""",
            """{"parser":"leaves_history","fixture":"hostel_requests.html","out":${json(Parsers.leaves(html("hostel_requests.html"), "LeaveHistoryTable"))}}""",
            """{"parser":"form_bonafide_page","fixture":"bonafide_page.html","out":${json(Forms.read(html("bonafide_page.html"), "studentBonafide"))}}""",
            """{"parser":"form_bonafide_page","fixture":"bonafide_form_3.html","out":${json(Forms.read(html("bonafide_form_3.html"), "studentBonafide"))}}""",
            """{"parser":"form_paper_seeing","fixture":"paper_seeing_page.html","out":${json(Forms.read(html("paper_seeing_page.html"), "paperSeeingView"))}}""",
            """{"parser":"form_course_feedback","fixture":"course_feedback_page.html","out":${json(Forms.read(html("course_feedback_page.html"), "courseFeedbackStudentView"))}}""",
        )
        File(target, "parity.json").writeText("[\n" + entries.joinToString(",\n") + "\n]\n")
    }

    /** Any model as JSON: a data class by its fields, in declaration order. */
    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v == Math.rint(v)) "%.1f".format(java.util.Locale.ENGLISH, v) else v.toString()
        is LocalDate, is LocalDateTime -> json(v.toString())
        is Enum<*> -> json(v.name)
        is Pair<*, *> -> "[${json(v.first)},${json(v.second)}]"
        is Collection<*> -> v.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${json(it.key.toString())}:${json(it.value)}" }
        else -> v.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .joinToString(",", "{", "}") { f ->
                f.isAccessible = true
                "${json(f.name)}:${json(f.get(v))}"
            }
    }
}
