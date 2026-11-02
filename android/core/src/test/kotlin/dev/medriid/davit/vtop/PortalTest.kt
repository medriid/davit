package dev.medriid.davit.vtop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PortalTest {
    @Test
    fun circularsReadTheTree() {
        val html = """<ul id="tree1"><li><span style="color: green;">Office of the Dean (Academics)</span><ul><li><span>GEN</span><ul><li><span>General</span><ul>
            <li><a onclick="javascript:viewCertificate('CIR2026000011');" href="javascript:void(0);">011_ACAD. CALEN. FOR WINTER SEMESTER 2026-27_07.09.2026.pdf [ 07-SEP-2026 ]</a></li>
            </ul></li></ul></li></ul></li></ul>"""
        val c = Portal.circulars(html).single()
        assertEquals("CIR2026000011", c.id)
        assertEquals("ACAD. CALEN. FOR WINTER SEMESTER 2026-27", c.title)
        assertEquals("2026-09-07", c.date)
        assertEquals("Office of the Dean (Academics)", c.office)
        assertEquals("General", c.group)
    }

    @Test
    fun projectsSplitTheTeacherFromTheSchool() {
        val html = """<table><thead><tr><th>PROJECT Title</th><th>RESEARCH AREA</th><th>POSTED ON</th><th>FACULTY DETAILS</th><th>ACTION</th></tr></thead>
            <tbody><tr><td>Example title</td><td>Mathematics</td><td>02-11-2026</td><td>Dr.ASHA RAMAN - SAS</td><td><button data-id="FP00000277">Go</button></td></tr></tbody></table>"""
        val p = Portal.projects(html).single()
        assertEquals("FP00000277", p.id)
        assertEquals("2026-11-02", p.posted)
        assertEquals("Dr.ASHA RAMAN", p.faculty)
        assertEquals("SAS", p.school)
    }

    @Test
    fun windowsAreShutOnlyWhenVtopSaysSo() {
        for (line in listOf("Registration Date is Over", "Internship registration date has been Closed", "No active registration found.", "Last date for applying is over !...", "You are not eligible for PAT Registration.", "Activity Not Permitted")) {
            val w = Portal.window("x", "X", "<h3>Page</h3><p>$line</p>")
            assertFalse(line, w.open)
            assertTrue(line, w.note.isNotBlank())
        }
        assertTrue(Portal.window("x", "X", "<h3>MOOC Course Registration</h3><form>Pick a course</form>").open)
    }

    @Test
    fun scriptsDoNotCloseAWindow() {
        assertTrue(Portal.window("x", "X", "<script>var m = 'Registration closed';</script><p>Pick a course</p>").open)
    }

    @Test
    fun signInsByHeader() {
        val html = """<table><tr><th>#</th><th>Activity Type</th><th>Date &amp; Time</th><th>Ip Address</th><th>App. Name</th></tr>
            <tr><td>1</td><td>LOGIN</td><td>02-Nov-2026 19:08:41</td><td>203.0.113.14</td><td>VTOP</td></tr></table>"""
        assertEquals(SignIn("LOGIN", "02-Nov-2026 19:08:41", "203.0.113.14", "VTOP"), Portal.signIns(html).single())
    }

    @Test
    fun sportsSeparateFacilitiesFromYours() {
        val html = """<table><tr><th>Facility Name</th><th>Fees (Including GST)</th><th>Number of seats - Available</th><th>Action</th></tr>
            <tr><td>Swimming Men </td><td>5310</td><td>487</td><td><button>Register</button></td></tr></table>
            <table><tr><td>Example Gym</td><td>Registered, Paid and Approved</td></tr></table>"""
        val s = Portal.sports(html)
        assertEquals(Facility("Swimming Men", "5310", "487"), s.facilities.single())
        assertEquals("Example Gym", s.mine.single().name)
    }

    @Test
    fun clubsReadBothTables() {
        val html = """<table><tr><th>#</th><th>Year</th><th>Association Name &amp; Type</th><th>Status</th><th>Team Name</th><th>Approved/Rejected</th></tr>
            <tr><td>1</td><td>2026</td><td>EXAMPLE GAMING CLUB (TAG) (CLUB)</td><td>Enrolled</td><td></td><td>Approved</td></tr></table>
            <table><tr><th>#</th><th>Year</th><th>Association Name (Type)</th><th>Status</th><th></th></tr>
            <tr><td>1</td><td>2026</td><td>THE EXAMPLE CLUB (CLUB)</td><td></td><td>Date Closed</td></tr></table>"""
        val d = Portal.clubDirectory(html)
        assertEquals(ClubRow("EXAMPLE GAMING CLUB (TAG)", "CLUB", "2026", "Enrolled"), d.mine.single())
        assertEquals(ClubRow("THE EXAMPLE CLUB", "CLUB", "2026", "Date Closed"), d.all.single())
    }

    @Test
    fun faqPanels() {
        val html = """<div class="panel panel-default"><a class="panel-title"><h4><span>A question?</span></h4></a><div class="panel-collapse">The answer.</div></div>"""
        assertEquals(FaqItem("HOSTEL", "GENERAL", "A question?", "The answer."), Portal.faqs(html, "HOSTEL", "GENERAL").single())
    }

    @Test
    fun malpracticeKeepsItsExams() {
        val table = "<table><tr><th>S.No</th><th>Nature of Malpractice</th><th>Actions</th></tr><tr><td>1.</td><td>Notes</td><td>Action</td></tr></table>"
        assertEquals(listOf("CAT and MTT", "FAT"), Portal.malpractice(table + table).map { it.exam })
    }

    @Test
    fun regulationsComeOutOfThePage() {
        val pdf = "%PDF-1.6 example".toByteArray()
        val html = "&lt;embed src=\"data:application/pdf;base64,${Base64.getEncoder().encodeToString(pdf)}\" width=\"100%\" type=\"application/pdf\"&gt;"
        assertArrayEquals(pdf, Portal.regulationsPdf(html))
        assertNull(Portal.regulationsPdf("<p>Nothing here</p>"))
    }

    @Test
    fun certificatesSendOnlyWhatTheFormTakes() {
        val html = """<form id="downloadForm" action="events/Gravitas/downloadParticipantCertificate"></form><table><tr class="tableContent"><td>1</td><td>2026</td><td>EXAMPLE HACK</td>
            <td><button onclick="javascript:downloadCertificate('2026', '2026004', 'ABC');">Download</button></td></tr></table>"""
        val c = Portal.gravitas(html).single()
        assertEquals("EXAMPLE HACK", c.title)
        assertEquals("/events/Gravitas/downloadParticipantCertificate", c.path)
        assertEquals(mapOf("eventId" to "2026004", "participantId" to "ABC"), c.params)
    }

    @Test
    fun noCertificateWithoutAForm() {
        assertTrue(Portal.universityDay("<table><tr><td colspan=3>No Certificates to Download</td></tr></table>").isEmpty())
    }
}
