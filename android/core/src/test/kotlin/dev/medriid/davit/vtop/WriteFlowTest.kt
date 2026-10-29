package dev.medriid.davit.vtop

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/**
 * Uploads, the OTP step, leave and downloads against a scripted VTOP (never the real one).
 * Also checks the guard: no write leaves the phone without the consent-taking methods.
 */
class WriteFlowTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()
    /** What the next upload POST answers with. */
    private var uploadReply = "upload_done.html"
    private var otpReply = "upload_done.html"

    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()
    private fun page(html: String) = MockResponse().setBody(html).addHeader("Content-Type", "text/html;charset=UTF-8")

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                bodies += request.body.clone().readUtf8()
                return when (request.requestUrl!!.encodedPath) {
                    "/vtop/examinations/doDigitalAssignment" -> page(res("assignments.html"))
                    "/vtop/examinations/processDigitalAssignment" -> page(res("assignment_detail_buttons.html"))
                    "/vtop/examinations/processDigitalAssignmentUpload" -> page(res("upload_form.html"))
                    "/vtop/examinations/doDAssignmentUploadMethod" -> page(res(uploadReply)).addHeader("error", "").addHeader("message", "")
                    "/vtop/examinations/doDAssignmentOtpUpload" -> page(res(otpReply))
                    "/vtop/hostels/student/leave/1" -> page("<div>Hostel Leave Request Screen</div>")
                    "/vtop/hostels/student/leave/2" -> page(res("leave_form.html"))
                    "/vtop/hostels/student/leave/3" -> page("<div id=\"Message\">Leave applied successfully</div>")
                    "/vtop/downloadCourseMaterialFacultyPdf" -> MockResponse()
                        .setBody(Buffer().write(ByteArray(150_000) { (it % 251).toByte() }))
                        .addHeader("Content-Type", "application/pdf")
                        .addHeader("Content-Disposition", "attachment; filename=\"FALL_BXYZ101_notes.pdf\"")
                    "/vtop/downloadSpotLightViewFile/101" -> page("<form id=\"vtopLoginForm\"></form>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun client() = VtopClient({ null }, CaptchaSolver.constant(), server.url("/vtop").toString(), pause = {})
        .apply { restoreSession("SESSION-CSRF\nREGNO") }

    private val consent = UserConsent("test")

    @Test
    fun firstUploadSendsTheWholeFormAndTheFile() {
        val c = client()
        val form = c.openUpload(consent, "VL20262701", "VL2026270100201", "CP")
        var last = 0L to 0L
        val outcome = c.upload(consent, form, "answer.pdf", "application/pdf", "%PDF-1.4 hello".toByteArray()) { a, b -> last = a to b }
        assertEquals(UploadOutcome.Uploaded("28 Oct 2026 11:36 PM"), outcome)
        val upload = bodies[seen.indexOfFirst { it.path!!.endsWith("doDAssignmentUploadMethod") }]
        assertTrue(seen.first { it.path!!.endsWith("doDAssignmentUploadMethod") }.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue("name=\"studDaUpload\"; filename=\"answer.pdf\"" in upload)
        assertTrue("%PDF-1.4 hello" in upload)
        assertTrue("name=\"mCode\"\r\nContent-Length: 2\r\n\r\nCP" in upload)
        // Both code/opt pairs go, as the browser's FormData sends them.
        assertEquals(2, Regex("name=\"opt\"").findAll(upload).count())
        // The token is the session's current one (the form page carried it).
        assertTrue("FORM-CSRF" in upload)
        assertEquals(last.second, last.first)
        assertTrue(last.first > 0)
    }

    @Test
    fun replacingAFileGoesThroughTheEmailedCode() {
        uploadReply = "upload_otp.html"
        val c = client()
        val form = c.openUpload(consent, "VL20262701", "VL2026270100201", "CP")
        val pending = c.upload(consent, form, "v2.pdf", "application/pdf", ByteArray(10)) as UploadOutcome.NeedsOtp
        assertEquals("st***@example.edu", pending.email)

        otpReply = "upload_otp_wrong.html"
        val again = c.submitUploadOtp(consent, pending, " 123456 ", "CP") as UploadOutcome.NeedsOtp
        assertEquals("Invalid OTP. Please try again.", again.error)
        val otpBody = bodies[seen.indexOfFirst { it.path!!.endsWith("doDAssignmentOtpUpload") }]
        assertTrue("name=\"otpEmail\"\r\nContent-Length: 6\r\n\r\n123456" in otpBody)

        otpReply = "upload_done.html"
        assertEquals(UploadOutcome.Uploaded("28 Oct 2026 11:36 PM"), c.submitUploadOtp(consent, again, "654321", "CP"))
    }

    @Test
    fun leaveGoesAsTheBrowserSendsIt() {
        val c = client()
        val form = c.leaveForm()!!
        val outcome = c.applyLeave(
            consent, form,
            LeaveRequest("HT1", "Chennai", LocalDateTime.of(2026, 11, 7, 9, 30), LocalDateTime.of(2026, 11, 9, 18, 0), "Family function"),
        )
        assertTrue(outcome.ok)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("/leave/3") }]
        listOf(
            "leaveCode=HT1", "visitingPlace=Chennai", "leaveFromDate=2026-11-07", "fromTime=09%3A30",
            "leaveToDate=2026-11-09", "toTime=18%3A00", "form=LeaveRequestForm", "control=submitControl2",
        ).forEach { assertTrue("missing $it in $sent", it in sent) }
    }

    @Test
    fun downloadsStreamWithTheirName() {
        val out = ByteArrayOutputStream()
        var calls = 0
        val d = client().downloadMaterial("2026000101", out) { _, _ -> calls++ }
        assertEquals("FALL_BXYZ101_notes.pdf", d.fileName)
        assertEquals("application/pdf", d.mimeType)
        assertEquals(150_000L, d.bytes)
        assertEquals(150_000, out.size())
        assertTrue(calls > 0)
        assertTrue(bodies.last().contains("fileId=2026000101"))
    }

    @Test
    fun aLoginPageInsteadOfAFileIsAnExpiredSession() {
        try {
            client().downloadSpotlight("downloadSpotLightViewFile/101", ByteArrayOutputStream())
            fail("expected SessionExpired")
        } catch (_: SessionExpired) {
        }
        // Spot-light files are GETs with the tokens in the query, like vtopDownload.
        assertEquals("GET", seen.last().method)
        assertTrue(seen.last().path!!.contains("_csrf=SESSION-CSRF"))
    }

    @Test
    fun nothingWritesWithoutTheConsentMethods() {
        val c = client()
        for (path in listOf("examinations/doDAssignmentUploadMethod", "examinations/doDAssignmentOtpUpload", "hostels/student/leave/3", "finance/otpVerfication")) {
            try {
                c.downloadSpotlight(path, ByteArrayOutputStream())
                fail("$path went through")
            } catch (_: Offline) {
            }
        }
        assertFalse(seen.any { it.path!!.contains("Upload") || it.path!!.contains("leave/3") || it.path!!.contains("otpVerfication") })
    }
}
