package dev.medriid.davit

import android.app.Application
import dev.medriid.davit.data.AcademicCalendar
import dev.medriid.davit.data.CalDay
import dev.medriid.davit.data.CachedCourse
import dev.medriid.davit.data.Campus
import dev.medriid.davit.data.HomeFeeds
import dev.medriid.davit.data.LibraryLine
import dev.medriid.davit.data.Repo
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.vtop.Assignment
import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.ClassGroup
import dev.medriid.davit.vtop.ClubEvent
import dev.medriid.davit.vtop.CourseAssignments
import dev.medriid.davit.vtop.CourseMarks
import dev.medriid.davit.vtop.CourseMaterials
import dev.medriid.davit.vtop.CourseOption
import dev.medriid.davit.vtop.CourseRemark
import dev.medriid.davit.vtop.CurriculumCategory
import dev.medriid.davit.vtop.CurriculumCourse
import dev.medriid.davit.vtop.Exam
import dev.medriid.davit.vtop.FacultyInfo
import dev.medriid.davit.vtop.FeeLetter
import dev.medriid.davit.vtop.FeedbackItem
import dev.medriid.davit.vtop.GradeEntry
import dev.medriid.davit.vtop.GradeHistory
import dev.medriid.davit.vtop.HostelInfo
import dev.medriid.davit.vtop.HostelRequests
import dev.medriid.davit.vtop.Leave
import dev.medriid.davit.vtop.MarkItem
import dev.medriid.davit.vtop.Material
import dev.medriid.davit.vtop.Person
import dev.medriid.davit.vtop.Profile
import dev.medriid.davit.vtop.Receipt
import dev.medriid.davit.vtop.RegistrationWindow
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.SpotlightItem
import dev.medriid.davit.vtop.TimetableCourse
import dev.medriid.davit.vtop.VtopMessage
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Debug builds only: generic made-up data for checking screens on an emulator without a
 * VTOP account. On when `files/demo` exists (`adb shell run-as dev.medriid.davit.dev touch files/demo`).
 * Nothing here is anyone's real data, and nothing talks to VTOP.
 */
internal object Demo {
    fun maybeShow(app: Application, repo: Repo) {
        if (!File(app.filesDir, "demo").exists()) return
        val today = LocalDate.now()
        val d = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
        val sem = Semester("VL20262701", "Fall Semester 2026-27")
        val timetable = listOf(
            TimetableCourse("VL2026270100101", "BXYZ101", "Applied Science", "Embedded Theory", 3.0, "A1+TA1", "SJT-401", "ASHA RAMAN"),
            TimetableCourse("VL2026270100102", "BXYZ101", "Applied Science", "Embedded Lab", 1.0, "L31+L32", "SJT-210", "ASHA RAMAN"),
            TimetableCourse("VL2026270100103", "BXYZ102", "Programming Basics", "Lab Only", 2.0, "L39+L40", "SJT-318", "VIKRAM SETH"),
            TimetableCourse("VL2026270100104", "BXYZ103", "Calculus", "Embedded Theory", 3.0, "C1+TC1", "MB-210", "MEERA NAIR"),
            TimetableCourse("VL2026270100105", "BXYZ104", "Technical English", "Embedded Theory", 2.0, "D1", "SMV-102", "RAHUL IYER"),
        )
        val attendance = listOf(
            AttendanceRow("BXYZ101", "Applied Science", "Embedded Theory", 30, 40, 75, "VL2026270100101"),
            AttendanceRow("BXYZ101", "Applied Science", "Embedded Lab", 11, 12, 92, "VL2026270100102"),
            AttendanceRow("BXYZ102", "Programming Basics", "Lab Only", 17, 25, 68, "VL2026270100103"),
            AttendanceRow("BXYZ103", "Calculus", "Embedded Theory", 38, 44, 87, "VL2026270100104"),
            AttendanceRow("BXYZ104", "Technical English", "Embedded Theory", 21, 26, 81, "VL2026270100105"),
        )
        val feeds = HomeFeeds(
            remarks = listOf(
                CourseRemark("BXYZ101", "Applied Science", "ETH", 75.0, "CAT1- Revoked"),
                CourseRemark("BXYZ101", "Applied Science", "ELA", 92.0, "Excellent - Keep going"),
                CourseRemark("BXYZ102", "Programming Basics", "LO", 68.0, "CAT1 - Debarred / CAT2 - Debarred"),
                CourseRemark("BXYZ103", "Calculus", "ETH", 87.0, "Good"),
                CourseRemark("BXYZ104", "Technical English", "ETH", 81.0, "Be cautious"),
            ),
            remarksAt = System.currentTimeMillis(),
            feedback = listOf(FeedbackItem("Mid-semester course feedback", "Academics", false), FeedbackItem("Mess feedback", "General", true)),
            events = listOf(
                ClubEvent(today, "Robotics Workshop", "Technical", "Robotics Club"),
                ClubEvent(today.plusDays(1), "Poetry Evening", "Literary", "Literary Society"),
                ClubEvent(today.plusDays(3), "Startup Pitch Night", "Entrepreneurship", "E-Cell"),
            ),
            proctorNote = "Meet me in my cabin on Friday at 4 pm if your attendance is under 75%.",
            registration = listOf(RegistrationWindow("Registration", "Winter Semester 2026-27", today.plusDays(3).atTime(17, 0), today.plusDays(3).atTime(17, 45))),
            dailyAt = System.currentTimeMillis(),
            classGroups = listOf(ClassGroup("ALL", "General (Semester)"), ClassGroup("ALL03", "General Freshers"), ClassGroup("ALL10", "General Freshers (Flexible-2)")),
        )
        val assignments = listOf(
            CourseAssignments(
                "VL2026270100103", "BXYZ102", "Programming Basics", "LO",
                listOf(
                    Assignment("Assessment - 1", 10.0, 10.0, today.minusDays(20).format(d), "02 Oct 2026 10:12 PM", code = "AST01", hasSubmission = true),
                    Assignment("Assessment - 2", 10.0, 10.0, today.plusDays(5).format(d), null, code = "AST02", hasQuestion = true, canUpload = true),
                    Assignment("Assessment - 3", 10.0, 10.0, today.plusDays(5).format(d), "26 Oct 2026 01:34 PM", code = "AST03", hasSubmission = true, canUpload = true),
                ),
            ),
            CourseAssignments(
                "VL2026270100101", "BXYZ101", "Applied Science", "ETH",
                listOf(Assignment("Drawing Workbook", 20.0, 13.0, today.plusDays(1).format(d), null, code = "CBDP", hasQuestion = true, canUpload = true)),
            ),
        )
        // A made-up day-by-day history for each course: its classes spread over the past weeks,
        // absences sprinkled through, adding up to the row's own totals.
        val history = attendance.associate { row ->
            val rnd = java.util.Random(row.classId.hashCode().toLong())
            val absent = (0 until row.total).shuffled(rnd).take(row.total - row.attended).toSet()
            "${row.courseCode}|${row.classType}" to (0 until row.total).map { i ->
                val day = today.minusDays(((row.total - i) * 70L / row.total).coerceAtLeast(1))
                dev.medriid.davit.vtop.AttendanceDay(
                    day.format(d), "A1",
                    if (i in absent) dev.medriid.davit.vtop.AttendanceDay.Status.Absent else dev.medriid.davit.vtop.AttendanceDay.Status.Present,
                )
            }
        }
        val snapshot = Snapshot(
            sem, listOf(sem, Semester("VL20252605", "Winter Semester 2025-26")), attendance, timetable, System.currentTimeMillis(),
            listOf("+ Mark: Calculus · Quiz 2 · 9/10", "− Programming Basics: absent · 17/25"),
            marks = listOf(CourseMarks("VL2026270100104", "BXYZ103", "Calculus", "ETH", listOf(MarkItem("Quiz 2", 10.0, 5.0, "Present", 9.0, 4.5, "")))),
            exams = listOf(Exam("VL2026270100104", "BXYZ103", "Calculus", "CAT2", today.plusDays(9).format(d), "FN", "09:30 AM - 11:00 AM", "SJT-401", "14 (R2)")),
            grades = GradeHistory(8.71, 23.0, listOf(GradeEntry("BAAA101", "Old Course", 4.0, "A", "May-2026"), GradeEntry("BAAA102", "Older Course", 3.0, "B", "May-2026"))),
            assignments = assignments,
            profile = Profile("Demo Student", "Male", null, HostelInfo("Q Block", "512", "4 Bed AC", "Veg")),
            profileAt = System.currentTimeMillis(),
            hostel = HostelRequests(activeLeave = listOf(Leave("Chennai", "Family function", "HOME TOWN", "07-Nov-2026", "09-Nov-2026", "Pending"))),
            hostelAt = System.currentTimeMillis(),
            feeds = feeds,
            faculty = listOf(
                FacultyInfo("10001", "ASHA RAMAN", "Associate Professor", "Department of Physics", "SAS", "asha.raman@example.edu", "SJT 411 A32"),
                FacultyInfo("10002", "VIKRAM SETH", "Assistant Professor", "Department of Software Systems", "SCOPE", "vikram.seth@example.edu", "PRP 327"),
                FacultyInfo("10003", "MEERA NAIR", "Professor", "Department of Mathematics", "SAS", "meera.nair@example.edu", "TT 524"),
            ),
            facultyAt = System.currentTimeMillis(),
            history = history,
            // This week's Saturday runs Wednesday's order; next Wednesday is a holiday.
            calendar = AcademicCalendar(
                sem.id, today.minusDays(100), today.plusDays(30), today.plusDays(30),
                (-100L..30L).map { today.plusDays(it) }.map { day ->
                    val saturday = today.with(java.time.DayOfWeek.SATURDAY)
                    when {
                        day == saturday -> CalDay(day, "Instructional", 3, null)
                        day == saturday.plusDays(4) -> CalDay(day, "Holiday", 0, "Founders' Day")
                        day.dayOfWeek.value >= 6 -> CalDay(day, "Holiday", 0, null)
                        else -> CalDay(day, "Instructional", day.dayOfWeek.value, null)
                    }
                },
                "General Freshers",
            ),
            calendarAt = System.currentTimeMillis(),
        )
        repo.showDemo(snapshot)
        DemoVtop.start(repo)

        val courses = listOf(
            CourseOption("VL_BXYZ101_00100", "VL20262701", "ETH", "Fall Semester 2026-27", "BXYZ101", "Applied Science", "Embedded Theory"),
            CourseOption("VL_BXYZ102_00100", "VL20262701", "LO", "Fall Semester 2026-27", "BXYZ102", "Programming Basics", "Lab Only"),
            CourseOption("VL_BXYZ201_00100", "VL20262705", "ETH", "Winter Semester 2026-27", "BXYZ201", "Data Structures", "Embedded Theory"),
        )
        val people = listOf("ASHA RAMAN" to "10001", "VIKRAM SETH" to "10002", "MEERA NAIR" to "10003", "KAVYA MENON" to "10004")
        val topics = listOf("Thermodynamics notes", "Battery chemistry", "Experiment", "Spectroscopy basics", "Polymers", "Corrosion", "Lab manual", "Question bank")
        fun page(c: CourseOption, n: Int) = CachedCourse(
            c,
            CourseMaterials(
                mapOf("1" to "Thermodynamics and kinetics", "2" to "Energy devices", "3" to "Functional materials", "4" to "Spectroscopy"),
                List(n) { i ->
                    val (who, id) = people[i % people.size]
                    Material(
                        (2026000100 + i + c.code.hashCode() % 1000).toString(), "${topics[i % topics.size]} ${i / topics.size + 1}",
                        ((i % 4) + 1).toString(), if (i % 7 == 0) "Video" else "Document", id, who, "SAS",
                        today.minusDays(i.toLong()).format(DateTimeFormatter.ofPattern("dd-MM-yyyy")), c.semesterName,
                    )
                },
            ),
            System.currentTimeMillis(),
        )
        repo.materials.showDemo(listOf(page(courses[0], 40), page(courses[1], 18)))
        repo.campus.showDemo(
            Campus(
                proctor = Person("Proctor", listOf("Faculty ID" to "10010", "Faculty Name" to "KAVYA MENON", "Faculty Designation" to "Professor", "Cabin" to "SJT 101 A1", "Faculty Email" to "kavya.menon@example.edu", "School" to "School of Sciences")),
                hodDean = listOf(
                    Person("Dean", listOf("Name of the Faculty" to "Dr. VIKRAM SETH", "Designation" to "Professor", "Cabin Number" to "SJT 301", "Email ID" to "dean.school@example.edu")),
                    Person("HOD", listOf("Name of the Faculty" to "Dr. MEERA NAIR", "Designation" to "Professor", "Cabin Number" to "SJT 302", "Email ID" to "hod.dept@example.edu")),
                ),
                peopleAt = System.currentTimeMillis(),
                classMessages = listOf(VtopMessage("ASHA RAMAN", "BXYZ101 - Applied Science", "Lab moves to SJT 210 tomorrow.", "27-Oct-2026")),
                messagesAt = System.currentTimeMillis(),
                spotlight = listOf(
                    SpotlightItem("Academics", "Mock course registration link", "https://example.org/mock", null),
                    SpotlightItem("Academics", "Student handbook", null, "downloadSpotLightViewFile/101"),
                    SpotlightItem("Events", "External participation form", null, "downloadSpotLightViewFile/102"),
                ),
                spotlightAt = System.currentTimeMillis(),
                receipts = listOf(Receipt("VL0000000001", "100001", "04-SEP-2026", 236.0, "Vellore"), Receipt("VL0000000002", "100002", "22-JUL-2026", 6490.0, "Vellore")),
                feeLetters = listOf(FeeLetter("4", "1", "COLLEGE FEES", "1001")),
                library = listOf(LibraryLine("Koha Due Amount", 0.0), LibraryLine("Total Due amount", 0.0)),
                moneyAt = System.currentTimeMillis(),
                categories = listOf(CurriculumCategory("UCC", "University Core Courses", 60.0, 60.0), CurriculumCategory("PCC", "Programme Core Courses", 12.0, 40.0)),
                curriculum = listOf(CurriculumCourse("University Core Courses", "BXYZ101", "Applied Science", "ETL", 4.0, "3-0-2-0")),
                curriculumAt = System.currentTimeMillis(),
                courses = courses,
                coursesAt = System.currentTimeMillis(),
            ),
        )
    }
}
