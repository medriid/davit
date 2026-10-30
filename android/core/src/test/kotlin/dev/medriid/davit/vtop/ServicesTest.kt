package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VTOP services added after 0.5.0 (faculty search, forms, records), against hand-written fixtures. */
class ServicesTest {
    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()

    @Test
    fun facultySearchReadsEachTeacherWithTheirId() {
        val hits = Parsers.facultySearch(res("faculty_search.html"))
        assertEquals(listOf("10001", "10077"), hits.map { it.empId })
        assertEquals("Dr. ASHA RAMAN", hits[0].name)
        assertEquals("Associate Professor Grade 1", hits[0].designation)
        assertEquals("School of Computer Science and Engineering", hits[1].school)
        assertTrue(Parsers.facultySearch(res("faculty_search_none.html")).isEmpty())
    }

    @Test
    fun aFormIsReadFieldByFieldWithItsLabelsAndButton() {
        val form = Forms.read(res("night_slip_form.html")).form!!
        assertEquals("LateHourRequestForm", form.id)
        assertEquals(listOf("venue", "eventType", "purpose", "facultyId", "fromDate", "toDate", "fromTime", "toTime"), form.fields.map { it.name })
        assertEquals(listOf("Venue", "Event Type", "Details", "Applied To"), form.fields.take(4).map { it.label })
        val venue = form.fields[0]
        assertEquals(FormField.Kind.Choice, venue.kind)
        assertEquals(listOf("TT" to "Technology Tower", "SJT" to "Silver Jubilee Tower", "LIB" to "Central Library"), venue.options)
        assertTrue(venue.required)
        assertEquals(100, form.fields[2].maxLength)
        assertEquals(listOf(FormField.Kind.Date, FormField.Kind.Date, FormField.Kind.Time, FormField.Kind.Time), form.fields.drop(4).map { it.kind })
        assertEquals(listOf("studentId" to "10077"), form.hidden)
        assertEquals("submitRequest", form.control)
        assertEquals("hostels/late/hour/student/request/3", form.submitTo)
    }

    @Test
    fun noFormMeansVtopsReason() {
        val page = Forms.read(res("night_slip_pending.html"))
        assertEquals(null, page.form)
        assertEquals("Already a request is pending for Approval", page.message)
    }

    @Test
    fun messChangeShowsYourMessAndWhetherItsOpen() {
        val m = Feeds.messChange(res("mess_change.html"))!!
        assertEquals("Mess Booking (2024 - 25)", m.title)
        assertEquals(listOf("EXAMPLE CATERERS [Q BLOCK]", "YOUR BOOKING IS CONFIRMED"), m.lines)
        assertEquals(false, m.open)
        val closed = Feeds.messChange(res("mess_change_closed.html"))!!
        assertEquals(listOf("Mess Change Closed"), closed.lines)
        assertEquals(false, closed.open)
    }

    @Test
    fun clubEventsKnowWhatCanBeRegisteredFor() {
        val p = Clubs.page(res("club_events.html"))
        assertEquals(listOf("SWF202600001", "SWF202600002", "SWF202600003"), p.upcoming.map { it.id })
        val first = p.upcoming[0]
        assertEquals("Line Follower Sprint", first.title)
        assertEquals("ROBOTICS CLUB (RC) (CLUB)", first.club)
        assertEquals("Build a small robot that follows a line, then race it.", first.description)
        assertEquals("11-Dec-2026 - 12-Dec-2026", first.dates)
        assertEquals("09:00 AM - 08:00 PM", first.time)
        assertEquals("2026-10-27 - 2026-12-11", first.registration)
        assertEquals("Example Hall (OFFLINE)", first.venue)
        assertTrue(first.canRegister)
        assertEquals(listOf(false, false), p.upcoming.drop(1).map { it.canRegister })
        assertEquals(listOf("Closed", "Limit Exceeded"), p.upcoming.drop(1).map { it.state })

        val r = p.registered.single()
        assertEquals("SWF202500077", r.id)
        assertEquals("ATTENDED", r.status)
        assertTrue(r.participation && r.winner)
        assertEquals(BoardCertificate("2025-26", "QUIZ CLUB", "Treasurer", "CLB042", "2026", "7"), p.board.single())
    }

    @Test
    fun generalEventCertificatesCarryTheirThreeIds() {
        assertEquals(listOf(GeneralCertificate("Example Hackathon 2026", "5501", "12", "880042")), Clubs.generalCertificates(res("general_certificates.html")))
    }

    @Test
    fun courseFeedbackListsEachClassAndWhetherItStillTakesFeedback() {
        val list = Clubs.classFeedback(res("course_feedback_list.html"))
        assertEquals(listOf("VL2026270100101", "VL2026270100190"), list.map { it.classId })
        val open = list[0]
        assertEquals("BXYZ101", open.code)
        assertEquals("Applied Science", open.title)
        assertEquals("Embedded Theory", open.type)
        assertEquals("ASHA RAMAN - SAS", open.faculty)
        assertEquals("A1+TA1", open.slot)
        assertTrue(open.open)
        val sent = list[1]
        assertEquals(false, sent.open)
        assertEquals("", sent.faculty)
        assertEquals("The weekly reviews helped because we fixed problems early.", sent.given)
    }

    @Test
    fun paperSeeingReadsItsChoicesAndTheCoursesOffered() {
        val form = Forms.read(res("paper_seeing_page.html"), "paperSeeingView").form!!
        assertEquals(listOf("semesterSubId", "serviceType", "examType"), form.fields.map { it.name })
        assertEquals(listOf("2" to "Paper Seeing", "1" to "Re - Evaluation"), form.fields[1].options)
        assertEquals(listOf("RGR" to "Regular", "ME_FAT" to "Makeup Exam"), form.fields[2].options)
        assertEquals("Select Exam Type", form.fields[2].label)

        val page = PaperSeeing.page(res("paper_seeing_courses.html"))
        assertEquals(listOf("VL2025260500311", "VL2025260500312"), page.courses.map { it.value })
        assertEquals("BXYZ201", page.courses[0].code)
        assertEquals("Data Structures", page.courses[0].title)
        assertTrue("Fee (Rs.)" to "500" in page.courses[0].details)
        assertTrue(page.courses[0].details.none { it.first == "Sl.No." })
        val none = PaperSeeing.page(res("paper_seeing_none.html"))
        assertTrue(none.courses.isEmpty())
        assertEquals("No Records Found/Grades have been published", none.message)
    }

    @Test
    fun qcmAndActivitiesReadAsTables() {
        val q = Feeds.table(res("qcm.html"))
        assertEquals("Course Code", q.headers[1])
        assertEquals("Suggestions", q.headers[8])
        assertEquals("From next week each class ends with two solved problems.", q.rows.single()[9])
        assertTrue(Feeds.table(res("activities_none.html")).rows.isEmpty())
        assertEquals(
            listOf(Semester("VL20262701", "Fall Semester 2026-27"), Semester("VL20252605", "Winter Semester 2025-26")),
            Records.semesters("<select name=\"semesterSubId\" id=\"semesterSubId\"><option value=\"\">-- Select --</option><option value=\"VL20262701\">Fall Semester 2026-27 - VLR</option><option value=\"VL20252605\">Winter Semester 2025-26 - VLR</option></select>"),
        )
    }

    @Test
    fun courseAllocationGoesCategoryCourseClasses() {
        val a = Records.allocation(res("allocation.html"))
        assertEquals("Winter Semester 2026-27", a.semester)
        assertEquals(listOf("NC", "UCC", "PCC"), a.categories.map { it.first })
        assertEquals(listOf("BXYZ201" to "BXYZ201 - Data Structures", "BXYZ202" to "BXYZ202 - Discrete Mathematics"), Records.options(res("allocation_courses.html"), "courseId"))
        val t = Feeds.table(res("allocation_classes.html"))
        assertEquals(listOf("Slot Detail", "Venue", "Faculty", "Course Type"), t.headers)
        assertEquals(listOf("A1+TA1", "SJT401", "ASHA RAMAN", "ETH"), t.rows[1])
    }

    @Test
    fun repliesReadAsDoneOnlyWhenTheySaySo() {
        assertTrue(Forms.reply("<p id=\"DataBlockMessage\">Late hour request submitted successfully</p>").ok)
        assertEquals(false, Forms.reply("<p id=\"DataBlockMessage\">Already a request is pending for Approval</p>").ok)
        assertEquals("Application Submitted Successfully.", Forms.reply("<input type=\"hidden\" id=\"info\" value=\"Application Submitted Successfully.\"/>").message)
        // Sweetalert pages: the words are in the script; "null" means nothing was said.
        assertEquals(Reply(true, "Feedback saved successfully"), Forms.reply("<script>var info = \"Feedback saved successfully\"; var infoMessage = null;</script><div>Course Feedback</div>"))
    }
}
