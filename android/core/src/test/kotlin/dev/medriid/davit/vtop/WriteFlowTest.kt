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
    private var slipForm = "night_slip_form.html"

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
                    "/vtop/hostels/messComplaintPage" -> page(res("mess_complaint_page.html"))
                    "/vtop/hostels/messComplaint/save" -> page("<div><span class=\"text-success\">Complaint submitted successfully</span></div>")
                    "/vtop/examinations/paperSeeing/PaperSeeing" -> page(res("paper_seeing_page.html"))
                    "/vtop/examinations/paperSeeing/PaperSeeingOnChange" -> page(res("paper_seeing_courses.html"))
                    "/vtop/examinations/paperSeeing/PaperSeeingSubmit" -> page("<input type=\"hidden\" id=\"success\" value=\"Invoice generated successfully. Pay on VTOP.\"/>")
                    "/vtop/academics/common/processViewFeedbackList" -> page(res("course_feedback_list.html"))
                    "/vtop/academics/common/saveAcademicsCourseFeedback" -> page("<script>var info = \"Feedback saved successfully\";</script>")
                    "/vtop/others/bonafide/StudentBonafidePageControl" -> page(res("bonafide_page.html"))
                    "/vtop/others/bonafide/StudentBonafideOnChange" -> page(res("bonafide_form_3.html"))
                    "/vtop/others/bonafide/StudentBonafideSave" -> page("<div id=\"main-section\"><input type=\"hidden\" id=\"info\" value=\"Application Submitted Successfully.\" /></div>")
                    "/vtop/event/swf/loadEventRegistration" -> page(res("club_events.html"))
                    "/vtop/event/swf/registered/doEventRegistraiton" -> page("<div id=\"main-section\"><h3 id=\"error2\">Registered successfully</h3></div>")
                    "/vtop/hostels/late/hour/student/request/1" -> page("<div>Late Hour Permission Screen</div>")
                    "/vtop/hostels/late/hour/student/request/2" -> page(res(slipForm))
                    "/vtop/hostels/late/hour/student/request/3" -> page("<p id=\"DataBlockMessage\">Request submitted successfully</p>")
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
    fun aNightSlipGoesAsTheFormSendsIt() {
        val reply = client().applyNightSlip(
            consent,
            mapOf("venue" to "TT", "eventType" to "LAB", "purpose" to "Project work", "facultyId" to "10021",
                "fromDate" to "2026-11-02", "toDate" to "2026-11-02", "fromTime" to "20:30", "toTime" to "23:30"),
        )
        assertTrue(reply.ok)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("request/3") }]
        listOf("venue=TT", "purpose=Project%20work", "fromTime=20%3A30", "studentId=10077", "form=LateHourRequestForm", "control=submitRequest", "_csrf=")
            .forEach { assertTrue("missing $it in $sent", it in sent) }
    }

    @Test
    fun aPendingSlipOrAChangedFormSendsNothing() {
        slipForm = "night_slip_pending.html"
        try {
            client().applyNightSlip(consent, emptyMap())
            fail("applied with no form")
        } catch (e: Refused) {
            assertEquals("Already a request is pending for Approval", e.message)
        }
        assertFalse(seen.any { it.path!!.endsWith("request/3") })
    }

    @Test
    fun registeringSendsTheEventOnlyWhileItsOpen() {
        val c = client()
        assertTrue(c.registerEvent(consent, "SWF202600001").ok)
        assertTrue("eventId=SWF202600001" in bodies[seen.indexOfFirst { it.path!!.endsWith("doEventRegistraiton") }])
        try {
            c.registerEvent(consent, "SWF202600003")
            fail("registered for a full event")
        } catch (e: Refused) {
            assertEquals("Limit Exceeded", e.message)
        }
        assertEquals(1, seen.count { it.path!!.endsWith("doEventRegistraiton") })
    }

    @Test
    fun bonafideReadsItsKindsAndFieldsAndSendsTheWholeForm() {
        val c = client()
        assertEquals(listOf("1" to "PASSPORT", "3" to "VISA (FOR VACATION)", "7" to "BUS PASS", "9" to "EDUCATION LOAN"), c.bonafideServices())
        val form = c.bonafideForm("3").form!!
        assertEquals(listOf("certificateCode", "fromDate", "toDate", "visaPlace", "description"), form.fields.map { it.name })
        assertEquals(listOf("From Date", "To Date", "Enter Visa Place"), form.fields.subList(1, 4).map { it.label })
        // Vacation visas: VTOP's script fills the dates and locks them.
        assertEquals(listOf("20-Dec-2026", "03-Jan-2027"), form.fields.subList(1, 3).map { it.value })
        assertTrue(form.fields.subList(1, 3).all { it.readOnly && it.kind == FormField.Kind.VtopDate })
        assertEquals(100, form.fields.last().maxLength)

        val reply = c.applyBonafide(consent, "3", mapOf("visaPlace" to "Example City", "description" to "For a family trip", "fromDate" to "01-Jan-2027"))
        assertEquals(Reply(true, "Application Submitted Successfully."), reply)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("StudentBonafideSave") }]
        assertTrue(seen.first { it.path!!.endsWith("StudentBonafideSave") }.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        listOf("certificateCode" to "3", "visaPlace" to "Example City", "description" to "For a family trip", "fromDate" to "20-Dec-2026", "_csrf" to "CSRF")
            .forEach { (k, v) -> assertTrue("missing $k=$v", Regex("name=\"$k\"\r\nContent-Length: \\d+\r\n\r\n${Regex.escape(v)}").containsMatchIn(sent)) }
    }

    @Test
    fun courseFeedbackGoesOnceAndOnlyToAnOpenClass() {
        val c = client()
        assertTrue(c.submitClassFeedback(consent, "VL20262701", "VL2026270100101", "  Worked examples before each lab helped.  ").ok)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("saveAcademicsCourseFeedback") }]
        listOf("semesterSubId=VL20262701", "classId=VL2026270100101", "courseFeedbackVal=Worked%20examples%20before%20each%20lab%20helped.")
            .forEach { assertTrue("missing $it in $sent", it in sent) }
        for ((id, text) in listOf("VL2026270100190" to "Again", "VL2026270100101" to "x".repeat(101), "VL2026270100101" to " ")) {
            try {
                c.submitClassFeedback(consent, "VL20262701", id, text)
                fail("sent $id")
            } catch (_: Refused) {
            }
        }
        assertEquals(1, seen.count { it.path!!.endsWith("saveAcademicsCourseFeedback") })
    }

    @Test
    fun paperSeeingSendsTheFormAndOnlyCoursesStillOffered() {
        val c = client()
        try {
            c.submitPaperSeeing(consent, "VL20252605", "2", "RGR", setOf("VL2025260500399"))
            fail("sent a course VTOP doesn't offer")
        } catch (_: Refused) {
        }
        assertTrue(c.submitPaperSeeing(consent, "VL20252605", "2", "RGR", setOf("VL2025260500311")).ok)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("PaperSeeingSubmit") }]
        listOf("semesterSubId=VL20252605", "serviceType=2", "examType=RGR", "frontValue=VL2025260500311", "regNoo=%24%7BregNo%7D")
            .forEach { assertTrue("missing $it in $sent", it in sent) }
        assertFalse("VL2025260500312" in sent)
        assertEquals(1, seen.count { it.path!!.endsWith("PaperSeeingSubmit") })
    }

    @Test
    fun aMessComplaintGoesWithItsPhotoWithinVtopsLimits() {
        val c = client()
        assertEquals(
            listOf("Please note that the mess complaint will be taken into account for your current dining caterer.",
                "A maximum of two complaints can be submitted per month.", "Only PDF or Image files up to 5MB are accepted."),
            c.messComplaintNotes(),
        )
        for ((text, name, size) in listOf(Triple("x".repeat(201), "a.jpg", 10), Triple("Cold rice", "a.gif", 10), Triple("Cold rice", "a.jpg", 5 * 1024 * 1024 + 1))) {
            try {
                c.sendMessComplaint(consent, text, name, "image/jpeg", ByteArray(size))
                fail("sent $name")
            } catch (_: Refused) {
            }
        }
        assertTrue(c.sendMessComplaint(consent, " Rice was cold at dinner ", "plate.jpg", "image/jpeg", "JPEGDATA".toByteArray()).ok)
        val sent = bodies[seen.indexOfFirst { it.path!!.endsWith("messComplaint/save") }]
        assertTrue(seen.first { it.path!!.endsWith("messComplaint/save") }.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue("name=\"complaintData\"\r\nContent-Length: 23\r\n\r\nRice was cold at dinner" in sent)
        assertTrue("name=\"complaintFile\"; filename=\"plate.jpg\"" in sent && "JPEGDATA" in sent)
        assertEquals(1, seen.count { it.path!!.endsWith("messComplaint/save") })
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
        for (path in listOf(
            "examinations/doDAssignmentUploadMethod", "examinations/doDAssignmentOtpUpload", "hostels/student/leave/3", "finance/otpVerfication",
            "hostels/late/hour/student/request/3", "hostels/late/hour/student/request/10",
            "event/swf/registered/doEventRegistraiton", "others/bonafide/StudentBonafideSave", "academics/common/saveAcademicsCourseFeedback",
            "examinations/paperSeeing/PaperSeeingSubmit", "processViewEcaInvoice", "cancleInvoiceGenerateEca",
            "hostels/messComplaint/save",
        )) {
            try {
                c.downloadSpotlight(path, ByteArrayOutputStream())
                fail("$path went through")
            } catch (_: Offline) {
            }
        }
        assertFalse(seen.any { it.path!!.contains("Upload") || it.path!!.contains("leave/3") || it.path!!.contains("otpVerfication") || it.path!!.contains("request/") })
    }
}
