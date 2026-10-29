package dev.medriid.davit.vtop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Course pages, assignment buttons and the upload pages, against hand-written fixtures. */
class CoursePagesTest {
    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()

    @Test
    fun courseOptionsSplitTheirLabel() {
        val c = CoursePages.courses(res("course_pages.html"))
        assertEquals(4, c.size)
        assertEquals("VL20262705", c[0].semesterId)
        assertEquals("Data Structures", c[0].name)
        // A name with " - " in it stays whole.
        assertEquals("Applied Science - Lab", c[1].name)
        assertEquals("ELA", c[2].type)
        assertEquals("Embedded Lab", c[2].typeName)
        assertEquals("BXYZ102", c[3].code)
        assertEquals("Fall Semester 2026-27", c[3].semesterName)
        assertTrue(c[1].key != c[2].key)
    }

    @Test
    fun materialsAndModules() {
        val m = CoursePages.materials(res("course_detail.html"))
        assertEquals(mapOf("1" to "Thermodynamics and kinetics", "2" to "Energy Devices"), m.modules)
        assertEquals(3, m.materials.size)
        val first = m.materials[0]
        assertEquals("2026000101", first.fileId)
        assertEquals("Module 2 Battery chemistry", first.title)
        assertEquals("2", first.module)
        assertEquals("Document", first.kind)
        assertEquals("10001", first.uploaderId)
        assertEquals("ASHA RAMAN", first.uploader)
        assertEquals("SAS", first.school)
        assertEquals("28-10-2026", first.date)
        assertEquals("Fall Semester 2026-27", first.semester)
        assertEquals("Video", m.materials[2].kind)
        assertEquals("Winter Semester 2025-26", m.materials[2].semester)
    }

    @Test
    fun assignmentButtonsSayWhatCanBeDone() {
        val a = Parsers.assignments(res("assignment_detail_buttons.html"))
        assertEquals(listOf("CBDP", "CP", "AST03"), a.map { it.code })
        assertTrue(a[0].hasQuestion && a[0].canUpload && !a[0].hasSubmission)
        assertNull(a[0].updated)
        assertTrue(a[1].hasSubmission && a[1].canUpload && !a[1].hasQuestion)
        assertEquals("26 Sep 2026 01:34 PM", a[1].updated)
        // Past the deadline: the file can still be fetched, but not replaced.
        assertFalse(a[2].canUpload)
        assertTrue(a[2].hasSubmission)
        assertEquals("03-Nov-2026", a[0].due)
    }

    @Test
    fun uploadFormKeepsEveryFieldButTheFile() {
        val f = CoursePages.uploadForm(res("upload_form.html"), "VL2026270100201", "CP")
        assertEquals("studDaUpload", f.fileField)
        assertEquals(
            listOf(
                "authorizedID" to "REGNO", "_csrf" to "FORM-CSRF", "classId" to "VL2026270100201", "mCode" to "CP",
                "code" to "CBDP", "opt" to "Drawing Workbook", "code" to "CP", "opt" to "Circuit Analysis",
            ),
            f.fields,
        )
    }

    @Test
    fun aClosedUploadSaysWhy() {
        try {
            CoursePages.uploadForm(res("upload_closed.html"), "VL1", "CP")
            fail("expected Refused")
        } catch (e: Refused) {
            assertEquals("Due date is over", e.message)
        }
    }

    @Test
    fun outcomes() {
        val done = CoursePages.uploadOutcome(res("upload_done.html"), "CP")
        assertEquals(UploadOutcome.Uploaded("28 Oct 2026 11:36 PM"), done)

        val otp = CoursePages.uploadOutcome(res("upload_otp.html"), "CP") as UploadOutcome.NeedsOtp
        assertEquals("st***@example.edu", otp.email)
        assertEquals("otpEmail", otp.otpField)
        assertEquals(listOf("authorizedID" to "REGNO", "_csrf" to "OTP-CSRF"), otp.fields)
        assertNull(otp.error)

        val wrong = CoursePages.uploadOutcome(res("upload_otp_wrong.html"), "CP") as UploadOutcome.NeedsOtp
        assertEquals("Invalid OTP. Please try again.", wrong.error)

        val header = CoursePages.uploadOutcome("<html><body></body></html>", "CP", errorHeader = "1", messageHeader = "File size exceeds")
        assertEquals(UploadOutcome.Refused("File size exceeds"), header)
        val silent = CoursePages.uploadOutcome("<html><body></body></html>", "CP")
        assertTrue(silent is UploadOutcome.Refused)
    }

    @Test
    fun fileNamesFromVtopsHeaders() {
        assertEquals("a b.pdf", fileNameOf("attachment; filename=\"a b.pdf\""))
        assertEquals("VIT-OBE Handbook.pdf", fileNameOf("attachment; filename=VIT-OBE Handbook.pdf"))
        assertEquals("notes.pdf", fileNameOf("attachment; filename*=UTF-8''notes.pdf"))
        assertEquals("evil.pdf", fileNameOf("attachment; filename=\"../../evil.pdf\""))
        assertNull(fileNameOf("attachment"))
        assertNull(fileNameOf(null))
    }
}
