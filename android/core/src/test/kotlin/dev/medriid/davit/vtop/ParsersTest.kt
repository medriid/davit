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
    fun spreadsheetLeftoversAreNotRemarks() {
        for (junk in listOf("SUM(G48:K48)", "sum(g48:k48)", "=G48+H48", "=SUM(A1:A5)", "AVERAGE(B2,C2)", "AVERAGE(B2, C2)", "ROUND(SUM(G48:K48),0)", "SUM(${'$'}G${'$'}48:${'$'}K${'$'}48)", "G48:K48", "#REF!", "#N/A", "#DIV/0!")) {
            assertTrue(junk, Parsers.isSheetLeftover(junk))
        }
        for (real in listOf("Absent for CAT", "Retest (Q2)", "Good", "Re-evaluated", "A1", "L19", "Reviewed(see Q3)", "Marks 45/50")) {
            assertFalse(real, Parsers.isSheetLeftover(real))
        }
    }

    @Test
    fun aFormulaInTheRemarkColumnIsDroppedAndTheMarkKept() {
        val page = """<div id="fixedTableContainer"><table class="customTable">
            <tr class="tableContent"><td>1</td><td>VL1</td><td>BXYZ101</td><td>Example</td><td>ETH</td><td>CAL</td><td>TEACHER</td><td>A1</td><td>Regular</td></tr>
            <tr class="tableContent"><td colspan="9"><table class="customTable-level1">
            <tr class="tableContent-level1"><td>1</td><td>CAT - II</td><td>50</td><td>15</td><td>Present</td><td>45.0</td><td>13.5</td><td>SUM(G48:K48)</td></tr>
            </table></td></tr></table></div>"""
        val item = Parsers.marks(page).single().items.single()
        assertEquals("", item.remark)
        assertEquals(45.0, item.scored!!, 0.0)
        assertEquals(13.5, item.weighted!!, 0.0)
    }

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
    fun timetableKeepsNextSemestersRegisteredRows() {
        val courses = Parsers.timetable(html("timetable_registered.html"))
        assertEquals(listOf("BXYZ201", "BXYZ202", "BXYZ205"), courses.map { it.courseCode })
        assertEquals(listOf(false, false, true), courses.map { it.approved })
        // An online course: no slot.
        assertEquals("", courses[1].slot)
        assertTrue(Parsers.timetable(html("timetable.html")).all { it.approved })
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
