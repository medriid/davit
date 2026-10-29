package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The home page feeds and the read-only record pages, against hand-written fixtures. */
class FeedsTest {
    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()

    @Test
    fun remarksReadDebarmentPerCourseAndType() {
        val r = Feeds.remarks(res("dash_courses.html"))
        assertEquals(listOf("ELA", "ETH", "LO"), r.map { it.type })
        assertEquals("BXYZ101", r[0].code)
        assertEquals("Applied Science", r[0].name)
        assertEquals(84.0, r[0].attendance!!, 0.0)
        assertFalse(r[0].serious)
        assertTrue(r[1].revoked && !r[1].debarred)
        assertEquals("CAT1 - Debarred / CAT2 - Debarred", r[2].remark)
        assertTrue(r[2].debarred)
    }

    @Test
    fun feedbackKnowsNotCompletedIsNotCompleted() {
        val f = Feeds.feedbacks(res("dash_feedback.html"))
        assertEquals(listOf(true, false), f.map { it.done })
        assertEquals("Mid-semester course feedback", f[1].title)
        assertEquals("Academics", f[1].category)
    }

    @Test
    fun eventsSkipEmptyDaysAndKeepTheClub() {
        val e = Feeds.events(res("dash_events.html"))
        assertEquals(listOf("Robotics Workshop", "Poetry Evening"), e.map { it.title })
        assertEquals(LocalDate.of(2026, 10, 31), e[0].date)
        assertEquals("Technical", e[0].category)
        assertEquals("Robotics Club", e[0].club)
    }

    @Test
    fun spotlightSplitsLinksFromFiles() {
        val s = Feeds.spotlight(res("content_spotlight.html"))
        assertEquals(3, s.size)
        assertEquals("Academics", s[0].section)
        assertEquals("https://example.org/mock-registration", s[0].link)
        assertNull(s[0].download)
        assertEquals("downloadSpotLightViewFile/101", s[1].download)
        assertEquals("Events", s[2].section)
    }

    @Test
    fun proctorAndHodDean() {
        val p = Feeds.proctor(res("proctor.html"))!!
        assertEquals("ASHA RAMAN", p.name)
        assertEquals("SJT 101 A1", p.cabin)
        assertEquals("asha.raman@example.edu", p.email)
        assertEquals("Professor", p.designation)
        val people = Feeds.hodDean(res("hod_dean.html"))
        assertEquals(listOf("Dean", "HOD"), people.map { it.role })
        assertEquals("Dr. MEERA NAIR", people[1].name)
        assertEquals("SJT 301", people[0].cabin)
    }

    @Test
    fun messagesEmptyOrAsATable() {
        assertTrue(Feeds.messages(res("messages_empty.html")).isEmpty())
        val m = Feeds.messages(res("messages_table.html")).single()
        assertEquals("ASHA RAMAN", m.from)
        assertEquals("Lab moved to SJT 210 tomorrow.", m.text)
        assertEquals("27-Oct-2026", m.date)
    }

    @Test
    fun gateLogKeepsItsColumns() {
        val t = Feeds.table(res("biometric.html"))
        assertEquals(listOf("Sl.No", "Date", "Time", "Location", "Status"), t.headers)
        assertEquals("OUT", t.rows[1][4])
        assertTrue(Feeds.table("<div>No Record(S) Found</div>").rows.isEmpty())
    }

    @Test
    fun receiptsLettersAndLibrary() {
        val r = Feeds.receipts(res("receipts.html"))
        assertEquals(listOf("100001", "100002"), r.map { it.number })
        assertEquals(6490.0, r[1].amount!!, 0.0)
        assertEquals("04-SEP-2026", r[0].date)
        val l = Feeds.feeLetters(res("fee_letters.html")).single()
        assertEquals("1001", l.batchId)
        assertEquals("COLLEGE FEES", l.description)
        val dues = Feeds.libraryDues(res("library.html"))
        assertEquals("Total Due amount", dues.last().first)
        assertEquals(12.5, dues.last().second!!, 0.0)
    }

    @Test
    fun registrationWindow() {
        val w = Feeds.registration(res("registration.html")).single()
        assertEquals("Winter Semester 2026-27", w.semester)
        assertEquals(LocalDateTime.of(2026, 11, 1, 17, 0), w.start)
        assertEquals(LocalDateTime.of(2026, 11, 1, 17, 45), w.end)
        assertEquals("Registration", w.title)
    }

    @Test
    fun curriculumCategoriesAndCourses() {
        val cats = Feeds.curriculumCategories(res("curriculum.html"))
        assertEquals(listOf("UCC", "PCC"), cats.map { it.id })
        assertEquals("Programme Core Courses", cats[1].name)
        assertEquals(12.0, cats[1].credits!!, 0.0)
        assertEquals(40.0, cats[1].max!!, 0.0)
        val courses = Feeds.curriculumCourses(res("curriculum_category.html"))
        assertEquals(listOf("BXYZ101", "BXYZ102", "BLAN101"), courses.map { it.code })
        assertEquals("Foreign Language", courses[2].group)
        assertEquals("3-0-2-0", courses[0].ltpj)
        assertEquals(2.0, courses[1].credits!!, 0.0)
    }

    @Test
    fun semesterGradesWithAndWithoutResults() {
        val g = Feeds.semesterGrades(res("grade_view.html"), "VL1")
        assertEquals(9.33, g.gpa!!, 0.0)
        assertEquals(listOf("A", "S"), g.courses.map { it.grade })
        assertEquals(4.0, g.courses[0].credits, 0.0)
        val none = Feeds.semesterGrades("<div>Select Semester No Records Found</div>", "VL2")
        assertNull(none.gpa)
        assertTrue(none.courses.isEmpty())
    }

    @Test
    fun leaveFormReadsTypesAndProctor() {
        val f = Feeds.leaveForm(res("leave_form.html"))!!
        assertEquals(listOf("HT1", "EY1", "WP"), f.types.map { it.first })
        assertEquals("HOME TOWN", f.types[0].second)
        assertEquals("ASHA RAMAN (10010)", f.proctor)
        assertEquals(listOf("_csrf" to "LEAVE-CSRF"), f.fields)
        assertNull(Feeds.leaveForm("<div>No hostel</div>"))
    }

    @Test
    fun leaveOutcomeIsCautious() {
        assertTrue(Feeds.leaveOutcome("<div>Leave applied successfully</div>").ok)
        assertFalse(Feeds.leaveOutcome("<div>Already a request is pending for Approval</div>").ok)
        assertFalse(Feeds.leaveOutcome("<div>Something odd</div>").ok)
    }
}
