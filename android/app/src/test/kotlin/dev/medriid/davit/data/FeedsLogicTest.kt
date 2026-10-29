package dev.medriid.davit.data

import dev.medriid.davit.vtop.AttendanceRow
import dev.medriid.davit.vtop.CourseRemark
import dev.medriid.davit.vtop.GradeEntry
import dev.medriid.davit.vtop.GradeHistory
import dev.medriid.davit.vtop.RegistrationWindow
import dev.medriid.davit.vtop.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Remarks on attendance cards, the alerts they raise, per-session GPA and the upload checks. */
class FeedsLogicTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val sem = Semester("VL1", "Fall")

    private fun row(code: String, type: String) = AttendanceRow(code, "Course $code", type, 30, 40, null, "")
    private fun snap(feeds: HomeFeeds) = Snapshot(sem, listOf(sem), emptyList(), emptyList(), 0, emptyList(), feeds = feeds)

    @Test
    fun remarksMatchTheoryAndLabSeparately() {
        val feeds = HomeFeeds(
            remarks = listOf(
                CourseRemark("BXYZ101", "Applied Science", "ELA", 84.0, "Good"),
                CourseRemark("BXYZ101", "Applied Science", "ETH", 75.0, "CAT1- Revoked"),
                CourseRemark("BXYZ102", "Programming", "LO", 68.0, "CAT1 - Debarred"),
            ),
        )
        assertEquals("CAT1- Revoked", feeds.remarkFor(row("BXYZ101", "Embedded Theory"))?.remark)
        assertEquals("Good", feeds.remarkFor(row("BXYZ101", "Embedded Lab"))?.remark)
        // One remark for the code matches whatever VTOP calls the type.
        assertEquals("CAT1 - Debarred", feeds.remarkFor(row("BXYZ102", "Lab Only"))?.remark)
        assertNull(feeds.remarkFor(row("BXYZ999", "Theory Only")))
    }

    @Test
    fun aRemarkTurningDebarredAlertsOnce() {
        val before = snap(HomeFeeds(remarks = listOf(CourseRemark("BXYZ102", "Programming", "LO", 74.0, "Be cautious"))))
        val after = snap(HomeFeeds(remarks = listOf(CourseRemark("BXYZ102", "Programming", "LO", 68.0, "CAT1 - Debarred"))))
        val alerts = alertsFor(before, after, today)
        assertEquals(listOf("Debarred: Programming"), alerts.map { it.title })
        assertTrue(alerts[0].text.contains("CAT1 - Debarred"))
        // The same remark next time is old news.
        assertTrue(alertsFor(after, after, today).none { it.title.startsWith("Debarred") })
        // Not on the very first remarks fetch: it's on screen already.
        assertTrue(alertsFor(snap(HomeFeeds()), after, today).isEmpty())
    }

    @Test
    fun aNewRegistrationWindowAlerts() {
        val w = RegistrationWindow("Registration", "Winter Semester 2026-27", LocalDateTime.of(2026, 11, 1, 17, 0), LocalDateTime.of(2026, 11, 1, 17, 45))
        val before = snap(HomeFeeds(dailyAt = 1))
        val after = snap(HomeFeeds(registration = listOf(w), dailyAt = 2))
        val alerts = alertsFor(before, after, today)
        assertEquals(listOf("Winter Semester 2026-27 registration"), alerts.map { it.title })
        assertEquals("Opens Sun 1 Nov, 5:00 PM · closes 5:45 PM", alerts[0].text)
        assertTrue(alertsFor(after, after, today).isEmpty())
        assertEquals(w, after.feeds.nextRegistration(LocalDateTime.of(2026, 11, 1, 17, 10)))
        assertNull(after.feeds.nextRegistration(LocalDateTime.of(2026, 11, 1, 18, 0)))
    }

    @Test
    fun feedsSurviveTheCache() {
        val f = HomeFeeds(
            remarks = listOf(CourseRemark("A", "B", "ETH", null, "Good")),
            registration = listOf(RegistrationWindow("R", "S", LocalDateTime.of(2026, 11, 1, 17, 0), null)),
            proctorNote = "Meet me",
            dailyAt = 5,
        )
        assertEquals(f, HomeFeeds.fromJson(f.toJson()))
        // Garbage or an older cache comes back empty, not as a crash.
        assertEquals(HomeFeeds(), HomeFeeds.fromJson("{not json"))
    }

    @Test
    fun sessionGpaCutsNothingAndSkipsUngraded() {
        val g = GradeHistory(
            null, null,
            listOf(
                GradeEntry("A1", "a", 4.0, "S", "Nov-2025"), GradeEntry("A2", "b", 2.0, "A", "Nov-2025"),
                GradeEntry("B1", "c", 3.0, "B", "May-2026"), GradeEntry("B2", "d", 1.0, "P", "May-2026"),
                GradeEntry("C1", "e", 3.0, null, "Nov-2026"),
            ),
        )
        val s = g.sessionGpas()
        assertEquals(listOf("Nov-2025", "May-2026"), s.map { it.session })
        assertEquals((4 * 10 + 2 * 9) / 6.0, s[0].gpa, 1e-9)
        assertEquals(8.0, s[1].gpa, 1e-9)
        assertEquals(3.0, s[1].credits, 0.0)
    }

    @Test
    fun uploadsAreCheckedLikeVtopDoes() {
        assertNull(Uploads.problemWith("answer.PDF", 10, ByteArray(10)))
        assertNull(Uploads.problemWith("sheet.xlsx", null, ByteArray(Uploads.MAX)))
        assertTrue(Uploads.problemWith("photo.jpg", 10, ByteArray(10))!!.contains("PDF, Word"))
        assertTrue(Uploads.problemWith("big.pdf", null, ByteArray(Uploads.MAX + 1))!!.contains("4 MB"))
        // A big file is judged by the size it reports, before it is read.
        assertEquals("\"big.pdf\" is 5.0 MB. VTOP takes 4 MB at most.", Uploads.problemWith("big.pdf", 5L * 1024 * 1024, null))
        assertTrue(Uploads.problemWith("empty.doc", 0, ByteArray(0))!!.contains("empty"))
        assertTrue(Uploads.problemWith("noext", 3, ByteArray(3)) != null)
    }

    @Test
    fun snapshotKeepsAssignmentButtonsAndFeeds() {
        val a = dev.medriid.davit.vtop.Assignment("DA 1", 10.0, 10.0, "03-Nov-2026", null, code = "CP", hasQuestion = true, hasSubmission = false, canUpload = true)
        val s = snap(HomeFeeds(proctorNote = "Hi", dailyAt = 9)).copy(
            assignments = listOf(dev.medriid.davit.vtop.CourseAssignments("VL1", "BXYZ101", "Applied Science", "ETH", listOf(a))),
        )
        val back = Snapshot.fromJson(s.toJson())!!
        assertEquals(a, back.assignments.single().items.single())
        assertEquals("Hi", back.feeds.proctorNote)
        assertEquals(9L, back.feeds.dailyAt)
    }

    @Test
    fun labCodes() {
        assertTrue(isLabCode("ELA") && isLabCode("LO") && isLabCode("lo"))
        assertTrue(!isLabCode("ETH") && !isLabCode("TH") && !isLabCode("EPJ"))
    }
}
