package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParsersTest {
    private fun html(name: String) = javaClass.getResource("/html/$name")!!.readText()

    @Test
    fun attendanceRows() {
        val rows = Parsers.attendance(html("attendance.html"))
        assertEquals(3, rows.size)
        val phy = rows[0]
        assertEquals("PHY1001", phy.courseCode)
        assertEquals("Engineering Physics", phy.courseName)
        assertEquals("Embedded Theory", phy.classType)
        assertEquals(37, phy.attended)
        assertEquals(50, phy.total)
        assertEquals(74, phy.vtopPercent)
        assertEquals("VL2026270100101", phy.classId)
        // a name containing " - " keeps it; the type is the last part
        assertEquals("Technical English - Communication", rows[2].courseName)
        assertEquals("Theory Only", rows[2].classType)
        assertEquals(82, rows[2].vtopPercent)
        assertEquals("", rows[2].classId)
    }

    @Test
    fun semesterDropdownSkipsPlaceholder() {
        val sems = Parsers.semesters(html("attendance.html"))
        assertEquals(listOf("VL20262701", "VL20252605"), sems.map { it.id })
        assertEquals("Fall Semester 2026-27 - VLR", sems[0].name)
    }

    @Test
    fun timetableKeepsRegisteredApprovedOnly() {
        val courses = Parsers.timetable(html("timetable.html"))
        assertEquals(listOf("PHY1001", "CSE2001", "STS1001"), courses.map { it.courseCode })
        val phy = courses[0]
        assertEquals("Engineering Physics", phy.courseName)
        assertEquals("Embedded Theory", phy.courseType)
        assertEquals("F1+TF1", phy.slot)
        assertEquals("SJT410", phy.venue)
        assertEquals("FACULTY A", phy.faculty)
        assertEquals(3.0, phy.credits, 0.0)
        assertEquals("VL2026270100101", phy.classId)
        // NIL slot: kept, but with no slot or venue
        assertEquals("", courses[2].slot)
        assertEquals("", courses[2].venue)
    }

    @Test
    fun loginOutcomes() {
        val wrongCaptcha = """<div class="alert alert-danger">Invalid Captcha</div><form id="vtopLoginForm"></form>"""
        val badCreds = """<div class="alert alert-danger">Invalid User Id / Password</div>"""
        val content = """<input type="hidden" id="authorizedIDX" value="REGNO"><input name="_csrf" value="c1">"""
        assertEquals(Parsers.LoginOutcome.WrongCaptcha, Parsers.loginOutcome(wrongCaptcha, "https://h/vtop/login"))
        assertEquals(Parsers.LoginOutcome.BadCredentials, Parsers.loginOutcome(badCreds, "https://h/vtop/login"))
        assertEquals(Parsers.LoginOutcome.Success, Parsers.loginOutcome(content, "https://h/vtop/content"))
        assertEquals(VtopSession("c1", "REGNO"), Parsers.session(content))
        assertNull(Parsers.session("<p>nothing</p>"))
    }

    @Test(expected = RecaptchaRequired::class)
    fun recaptchaIsReported() {
        Parsers.loginPage("""<form id="vtopLoginForm"><div class="g-recaptcha"></div></form>""")
    }

    @Test
    fun loggedOutDetection() {
        assertTrue(Parsers.isLoggedOutPage("", "https://vtop.vit.ac.in/vtop/login"))
        assertTrue(Parsers.isLoggedOutPage("""<form id="vtopLoginForm">""", "https://vtop.vit.ac.in/vtop/processViewTimeTable"))
        assertFalse(Parsers.isLoggedOutPage(html("timetable.html"), "https://vtop.vit.ac.in/vtop/processViewTimeTable"))
    }

    @Test
    fun classIds() {
        assertEquals("VL2026270100101", Parsers.normalizeClassId(" vl 2026270100101 "))
        assertEquals("", Parsers.normalizeClassId("VL12"))
    }

    @Test
    fun slotTimes() {
        assertEquals(listOf("L31+L32", "L45+L46"), SlotTimes.splitSlots("L31+L32+L45+L46"))
        assertEquals(listOf("A1", "TA1"), SlotTimes.splitSlots("A1+TA1"))
        val a1 = SlotTimes.meetings("A1+TA1")
        assumeTrue("slot_times.txt not supplied (README)", a1.isNotEmpty())
        // A1: Mon 08:00, Wed 09:00; TA1: Fri 10:00
        assertEquals(listOf(1 to 480, 3 to 540, 5 to 600), a1.map { it.day to it.start })
        val lab = SlotTimes.meetings("L31+L32").single()
        assertEquals(1, lab.day)
        assertEquals(14 * 60, lab.start)
        assertEquals(15 * 60 + 40, lab.end)
    }
}
