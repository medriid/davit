package dev.medriid.davit

import dev.medriid.davit.data.Repo
import dev.medriid.davit.vtop.VtopClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder

/**
 * Debug builds only: a pretend VTOP on the phone itself, so the screens that open VTOP's
 * forms (and send them, after the confirm) can be tried in demo mode. Every page is made
 * up; the real VTOP is never contacted, and nothing sent here goes anywhere.
 */
internal object DemoVtop {
    fun start(repo: Repo) {
        Thread {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty().removePrefix("/vtop/")
                    val form = params(request)
                    if (path in pdfs) {
                        return MockResponse().setBody(okio.Buffer().writeUtf8(DEMO_PDF)).addHeader("Content-Type", "application/pdf")
                            .addHeader("Content-Disposition", "attachment; filename=\"demo-certificate.pdf\"")
                    }
                    val html = page(path, form) ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(html).addHeader("Content-Type", "text/html;charset=UTF-8")
                }
            }
            server.start()
            repo.demoClient = VtopClient({ null }, null, server.url("/vtop").toString()).apply { restoreSession("DEMO-CSRF\nDEMO") }
        }.apply { isDaemon = true }.start()
    }

    /** Form fields of a POST, urlencoded or multipart (a file part's bytes come along as text; only the names matter here). */
    private fun params(r: RecordedRequest): Map<String, String> {
        val body = r.body.clone().readUtf8()
        if (r.getHeader("Content-Type")?.startsWith("multipart/") == true) {
            return Regex("name=\"([^\"]+)\"[^\r]*\r\n(?:[^\r\n]+\r\n)*\r\n([^\r]*)").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        }
        if (r.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") != true) return emptyMap()
        return body.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    private fun page(path: String, p: Map<String, String>): String? = when (path) {
        "hrms/employeeSearchForStudent" -> "<div><strong>Faculty Information</strong></div>"
        "hrms/EmployeeSearchForStudent" -> facultySearch(p["empId"].orEmpty())
        "hrms/EmployeeSearch1ForStudent" -> faculty.firstOrNull { it[0] == p["empId"] }?.let { f ->
            """<table>
              <tr><td><b>Name of the Faculty</b></td><td>${f[1]}</td></tr>
              <tr><td><b>Designation</b></td><td>${f[2]}</td></tr>
              <tr><td><b>Name of Department</b></td><td>None</td></tr>
              <tr><td><b>School / Centre Name</b></td><td>${f[3]}</td></tr>
              <tr><td><b>E-Mail Id</b></td><td>${f[4]}</td></tr>
              <tr><td><b>Cabin Number</b></td><td>${f[5]}</td></tr></table>"""
        } ?: "<div>No records found</div>"
        "academics/common/ExtraCurricular", "academics/common/QCMStudentLogin" -> """<select name="semesterSubId" id="semesterSubId"><option value="">-- Choose Semester --</option>
            <option value="VL20262701">Fall Semester 2026-27</option><option value="VL20252605">Winter Semester 2025-26</option></select>"""
        "processViewEca" -> if (p["semesterSubId"] == "VL20252605") {
            "<table><tr><th>S.No</th><th>Activity</th><th>Category</th><th>Hours</th><th>Status</th></tr>" +
                "<tr><td>1</td><td>Campus clean-up drive</td><td>Social service</td><td>6</td><td>Approved</td></tr>" +
                "<tr><td>2</td><td>Inter-college quiz</td><td>Literary</td><td>3</td><td>Approved</td></tr></table>"
        } else "<div id=\"getStudentDetails\"><strong>No Records Found</strong></div>"
        "getStudentLoginForQcm" -> if (p["semSubId"] == "VL20262701") {
            "<table><tr><td>Sem Code</td><td>Course Code</td><td>Course Title</td><td>Course Type</td><td>Class Nbr</td><td>Faculty</td><td>QCM No.</td><td>Action</td><td>Suggestions</td><td>Faculty Reply</td><td>HOD Comments</td></tr>" +
                "<tr><td>FALLSEM2026-27</td><td>BXYZ103</td><td>Calculus</td><td>ETH</td><td>VL2026270100104</td><td>MEERA NAIR</td><td>1</td><td>Faculty and HOD</td>" +
                "<td>More worked examples in class, please</td><td>From next week each class ends with two solved problems.</td><td></td></tr></table>"
        } else "<table><tr><td>Sem Code</td><td>Course Code</td></tr></table>"
        "academics/common/StudentRegistrationScheduleAllocation" -> """<div>Course Allocation Details</div><div><label>Semester</label><span>Winter Semester 2026-27</span></div>
            <select name="curriculumCategory" id="curriculumCategory"><option value="">-- Choose --</option><option value="UCC">UCC - University Core Courses</option>
            <option value="PCC">PCC - Programme Core Courses</option><option value="OEC">OEC - Open Elective Courses</option></select>"""
        "academics/common/getCoursesListForCurriculmCategory" -> "<select name=\"courseId\" id=\"courseId\"><option value=\"\">-- Choose --</option>" +
            (if (p["cccategory"] == "OEC") "" else "<option value=\"BXYZ201\">BXYZ201 - Data Structures</option><option value=\"BXYZ202\">BXYZ202 - Discrete Mathematics</option>") + "</select>"
        "academics/common/getCoursesDetailForRegistration" -> "<table><thead><tr><th>Slot Detail</th><th>Venue</th><th>Faculty</th><th>Course Type</th></tr></thead><tbody>" +
            "<tr><td>A1+TA1</td><td>SJT401</td><td>ASHA RAMAN</td><td>ETH</td></tr><tr><td>L37+L38+L51+L52</td><td>PRP231</td><td>RAMAN K</td><td>ELA</td></tr></tbody></table>"
        "examinations/paperSeeing/PaperSeeing" -> paperSeeing(emptyMap())
        "examinations/paperSeeing/PaperSeeingOnChange" -> paperSeeing(p)
        "examinations/paperSeeing/PaperSeeingSubmit" ->
            "<input type=\"hidden\" id=\"success\" value=\"Request submitted successfully. Invoice generated; pay it under Payments.\"/>"
        "academics/common/StudentClassGrievance" -> """<form id="courseFeedbackStudentView"><label>Semester</label><select name="semesterSubId" id="semesterSubId">
            <option value="">--Choose Semester--</option><option value="VL20262701">Fall Semester 2026-27</option></select></form>"""
        "academics/common/processViewFeedbackList" -> feedbackList()
        "academics/common/saveAcademicsCourseFeedback" -> {
            p["classId"]?.let { feedback[it] = p["courseFeedbackVal"].orEmpty() }
            "<script>var info = \"Feedback submitted successfully\"; var infoMessage = null;</script><div>Course Feedback System 24x7</div>"
        }
        "others/bonafide/StudentBonafidePageControl" -> bonafide(null)
        "others/bonafide/StudentBonafideOnChange" -> bonafide(p["certificateCode"])
        "others/bonafide/StudentBonafideSave" -> "<div id=\"main-section\"><input type=\"hidden\" id=\"info\" value=\"Application Submitted Successfully.\" /></div>"
        "event/swf/loadEventRegistration" -> clubPage()
        "event/swf/registered/doEventRegistraiton" -> {
            p["eventId"]?.let { registered += it }
            "<div id=\"main-section\"><h3 id=\"error2\">Registered successfully</h3></div>"
        }
        "events/swf/certificateDownload" -> """<table id="dataTable1"><thead><tr><th>Event Name</th><th>Download</th></tr></thead>
            <tbody><tr><td>Example Hackathon 2026</td><td><button onclick="downloadCertificate('5501','12','880042');">Download</button></td></tr></tbody></table>"""
        "hostels/messComplaintPage" -> """<div class="card"><span class="ms-5"><u>Note:</u><br/> 1. Please note that the mess complaint will be taken into account for your current dining caterer.<br/>
            2. A maximum of two complaints can be submitted per month.<br/> 3. Only PDF or Image files up to 5MB are accepted.<br/></span>
            <form id="messComplaintForm"><textarea name="complaintData" maxlength="200"></textarea><input type="file" name="complaintFile"></form></div>"""
        "hostels/messComplaint/save" -> "<div><span class=\"text-success\">Complaint submitted successfully</span></div>"
        "hostels/onlineMessChange" -> """<div class="card"><div class="card-header"><strong>Mess Booking (2026 - 27)</strong></div>
            <div class="card-body"><h4 align="center">EXAMPLE CATERERS [Q BLOCK] <BR/> YOUR BOOKING IS CONFIRMED</h4></div></div>"""
        // Hostel: leave and night slips. A slip sent here shows up as pending, as on VTOP.
        "hostels/late/hour/student/request/1", "hostels/student/leave/1" -> "<div>Hostel</div>"
        "hostels/late/hour/student/request/2" ->
            if (slip != null) "<p style=\"display: none;\" id=\"DataBlockMessage\"> Already a request is pending for Approval</p>" else NIGHT_SLIP_FORM
        "hostels/late/hour/student/request/3" -> {
            slip = p
            "<p id=\"DataBlockMessage\">Late hour request submitted successfully</p>"
        }
        "hostels/late/hour/student/request/9" -> "<table id=\"LateHourStatusTable\"><tbody>" + (slip?.let { s ->
            "<tr><td><input type=\"hidden\" value=\"P1\"></td><td><input type=\"button\" value=\"Cancel\"></td><td>${venues[s["venue"]] ?: s["venue"]}</td><td>${s["eventType"]}</td>" +
                "<td>${s["purpose"]}</td><td>RAMAN K</td><td>${s["fromDate"]}</td><td>${s["toDate"]}</td><td>${s["fromTime"]} to ${s["toTime"]}</td><td>REQUEST RAISED-APPROVAL PENDING</td></tr>"
        } ?: "") + "</tbody></table>"
        "hostels/late/hour/student/request/11" -> "<table id=\"LateHourHistoryTable\"><tbody></tbody></table>"
        "hostels/student/leave/4" -> "<table id=\"LeaveAppliedTable\"><tbody><tr><td>1</td><td><input type=\"hidden\" value=\"y\"></td><td>Chennai</td><td>Family function</td><td>HOME TOWN</td><td>07-Nov-2026</td><td>09-Nov-2026</td><td>Pending</td></tr></tbody></table>"
        "hostels/student/leave/6" -> "<table id=\"LeaveHistoryTable\"><tbody></tbody></table>"
        // Assignments: a first upload lands; replacing a file asks for the emailed code, which is 123456 here.
        "examinations/doDigitalAssignment", "examinations/processDigitalAssignment" -> "<div>Digital Assignment Upload</div>"
        "examinations/processDigitalAssignmentUpload" -> """<form id="daUpload" name="daUpload" method="post">
            <input type="hidden" name="authorizedID" value="DEMO"><input type="hidden" name="_csrf" value="DEMO-CSRF">
            <input type="hidden" name="classId" value="${p["classId"].orEmpty()}"><input type="hidden" name="mCode" value="${p["mode"].orEmpty()}">
            <input type="hidden" name="code" value="${p["mode"].orEmpty()}"><input type="file" name="studDaUpload"></form>"""
        "examinations/doDAssignmentUploadMethod" -> p["mCode"].orEmpty().let { code ->
            if (code in submitted) {
                otpFor = code
                """<div><p>Kindly enter 6 digit OTP sent to your email ID de***@example.edu</p>
                <form id="daUploadOtpAlert"><input type="hidden" name="authorizedID" value="DEMO"><input type="hidden" name="_csrf" value="DEMO-CSRF">
                <input type="text" name="otpEmail" maxlength="6"></form></div>"""
            } else {
                submitted += code
                UPLOADED
            }
        }
        "examinations/doDAssignmentOtpUpload" -> if (p["otpEmail"] == "123456") {
            otpFor?.let { submitted += it }
            UPLOADED
        } else {
            """<span style="color: red;">Invalid OTP. Please try again.</span><p>Kindly enter 6 digit OTP sent to your email ID de***@example.edu</p>
            <form id="daUploadOtpAlert"><input type="hidden" name="_csrf" value="DEMO-CSRF"><input type="text" name="otpEmail" maxlength="6"></form>"""
        }
        else -> null
    }

    /** Assignments that already have a file (so uploading again asks for the code), and the one waiting for it. */
    private val submitted = mutableSetOf("AST01", "AST03")
    private var otpFor: String? = null
    private const val UPLOADED = "<div><span style=\"color: green;\">Uploaded successfully</span></div>"

    private val pdfs = setOf(
        "events/swf/doDownloadCertificate", "events/swf/doDownloadWinnerCertificate",
        "events/swf/boardMembersCertificate/doDownloadCertificate", "events/downloadCertificate",
    )

    /** A one-page blank PDF, for the demo's certificates. */
    private const val DEMO_PDF = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
        "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"

    private val registered = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** id, club, title, description, dates, time, registration, venue, state ("" = open). */
    private val clubEvents = listOf(
        listOf("SWF1", "ROBOTICS CLUB (RC) (CLUB)", "Line Follower Sprint", "Build a small robot that follows a line, then race it against the clock.", "11-Dec-2026 - 12-Dec-2026", "09:00 AM - 08:00 PM", "2026-10-27 - 2026-12-11", "Example Hall (OFFLINE)", ""),
        listOf("SWF2", "LITERARY SOCIETY (CLUB)", "Poetry Evening", "Read a poem of your own or someone else's.", "02-Nov-2026 - 02-Nov-2026", "06:00 PM - 07:30 PM", "2026-10-24 - 2026-11-02", "Online Meet (ONLINE)", ""),
        listOf("SWF3", "E-CELL (CHAPTER)", "Pitch Night", "Pitch an idea in three minutes.", "29-Oct-2026 - 29-Oct-2026", "05:00 PM - 08:00 PM", "2026-10-21 - 2026-10-29", "Example Gallery (OFFLINE)", "Closed"),
        listOf("SWF4", "QUIZ CLUB (CLUB)", "Weekend Quiz", "Teams of three.", "31-Oct-2026 - 31-Oct-2026", "04:00 PM - 06:00 PM", "2026-10-20 - 2026-10-30", "Example Hall (OFFLINE)", "Limit Exceeded"),
    )

    private fun clubPage(): String {
        fun row(e: List<String>, last: String) = "<tr><td>1</td><td>${e[1]}</td><td><span>${e[2]}</span></td>" +
            "<td><button onclick=\"doShowEventdescription('${e[0]}');\"></button><div class=\"modal\"><div class=\"modal-body\"><span>${e[3]}</span></div></div></td>" +
            "<td>${e[4]}</td><td>${e[5]}</td><td>1.0</td><td>${e[6]}</td><td>${e[7]}</td>$last</tr>"
        val head = "<th>#</th><th>Association</th><th>Event Title</th><th>Description</th><th>Event Date</th><th>Event Time</th><th>Days</th><th>Registration Date</th><th>Venue</th>"
        val upcoming = clubEvents.filter { it[0] !in registered }.joinToString("") { e ->
            row(e, if (e[8].isEmpty()) "<td><button onclick=\"javascript:doEventRegistraiton('${e[0]}');\"> Register</button></td>" else "<td><span style=\"color:red\">${e[8]}</span></td>")
        }
        val mine = (clubEvents.filter { it[0] in registered }.map { row(it, "<td>REGISTERED</td><td></td><td></td>") } +
            row(listOf("SWF0", "QUIZ CLUB (CLUB)", "General Quiz", "", "12-Sep-2026 - 12-Sep-2026", "04:00 PM - 06:00 PM", "2026-09-01 - 2026-09-11", "Example Hall (OFFLINE)"),
                "<td>ATTENDED</td><td><button name=\"downloadBtn1\" data-eventid=\"SWF0\">Download</button></td><td><button name=\"downloadBtn2\" data-eventid=\"SWF0\">Download</button></td>")).joinToString("")
        return "<table id=\"dataTable1\"><thead><tr>$head<th>Register</th></tr></thead><tbody>$upcoming</tbody></table>" +
            "<table id=\"dataTable2\"><thead><tr>$head<th>Status</th><th>Certificate</th><th>Achiever</th></tr></thead><tbody>$mine</tbody></table>" +
            "<table id=\"dataTable3\"><thead><tr><th>#</th><th>Year</th><th>Club Name</th><th>Position</th><th>Download</th></tr></thead><tbody>" +
            "<tr><td>1</td><td>2025-26</td><td>QUIZ CLUB</td><td>TREASURER</td><td><button name=\"downloadBtn\" data-associationid=\"CLB042\" data-generatedyear=\"2026\" data-roleid=\"7\">Download</button></td></tr></tbody></table>"
    }

    /** The paper seeing form; with [p] chosen, last winter's results offer two courses and other terms none. */
    private fun paperSeeing(p: Map<String, String>): String {
        val sem = p["semesterSubId"].orEmpty()
        fun opt(v: String, l: String, now: String) = "<option value=\"$v\"${if (v == now) " selected" else ""}>$l</option>"
        val rows = if (sem == "VL20252605" && p["examType"] == "RGR") {
            "<table><thead><tr><th>Select</th><th>Sl.No.</th><th>Course Code</th><th>Course Title</th><th>Course Type</th><th>Grade</th><th>Fee (Rs.)</th></tr></thead><tbody>" +
                "<tr><td><input type=\"checkbox\" name=\"frontValue\" value=\"VL2025260500311\"></td><td>1</td><td>BAAA101</td><td>Old Course</td><td>ETH</td><td>A</td><td>500</td></tr>" +
                "<tr><td><input type=\"checkbox\" name=\"frontValue\" value=\"VL2025260500312\"></td><td>2</td><td>BAAA102</td><td>Older Course</td><td>TH</td><td>B</td><td>500</td></tr></tbody></table>"
        } else ""
        val bom = if (p.isNotEmpty() && rows.isEmpty()) "No Records Found/Grades have been published" else ""
        return "<form id=\"paperSeeingView\"><input type=\"hidden\" name=\"regNoo\" value=\"\${regNo}\"/>" +
            "<div><label for=\"semesterSubId\">Select Semester</label><select name=\"semesterSubId\" id=\"semesterSubId\"><option value=\"\">-- Choose Semester --</option>" +
            opt("VL20262701", "Fall Semester 2026-27", sem) + opt("VL20252605", "Winter Semester 2025-26", sem) + opt("VL20252601", "Fall Semester 2025-26", sem) + "</select></div>" +
            "<div><label for=\"serviceType\">Select Service</label><select name=\"serviceType\" id=\"serviceType\"><option value=\"\">-- Choose Service --</option>" +
            opt("2", "Paper Seeing", p["serviceType"].orEmpty()) + opt("1", "Re - Evaluation", p["serviceType"].orEmpty()) + "</select></div>" +
            "<div><label>Select Exam Type</label><div><label><input type=\"radio\" name=\"examType\" value=\"RGR\"> Regular</label>" +
            "<label><input type=\"radio\" name=\"examType\" value=\"ME_FAT\"> Makeup Exam</label></div></div>$rows" +
            "<input type=\"hidden\" id=\"jsonBom\" value=\"$bom\"/></form>"
    }

    private val feedback = java.util.concurrent.ConcurrentHashMap(mapOf("VL2026270100105" to "The reading lists each week made the essays easier to plan."))

    /** class, code - title, type, faculty, slot, venue. */
    private val feedbackClasses = listOf(
        listOf("VL2026270100101", "BXYZ101 - Applied Science", "Embedded Theory", "ASHA RAMAN - SAS", "A1+TA1", "SJT401"),
        listOf("VL2026270100102", "BXYZ101 - Applied Science", "Embedded Lab", "ASHA RAMAN - SAS", "L31+L32", "SJT210"),
        listOf("VL2026270100103", "BXYZ102 - Programming Basics", "Lab Only", "VIKRAM SETH - SCOPE", "L39+L40", "SJT318"),
        listOf("VL2026270100105", "BXYZ104 - Technical English", "Embedded Theory", "RAHUL IYER - SSL", "D1", "SMV102"),
    )

    private fun feedbackList(): String = "<table>" + feedbackClasses.joinToString("") { c ->
        val sent = feedback[c[0]]
        val action = if (sent == null) {
            "<tr><td><textarea id=\"courseFeedback_${c[0]}\" name=\"courseFeedback\"></textarea></td><td><button type=\"button\" value=\"${c[0]}\" onclick=\"processSaveCourseFeedback(this.value);\">Submit</button></td></tr>"
        } else "<tr><td>$sent</td><td>Submitted</td></tr>"
        "<tr><td><div class=\"box\"><h5 class=\"box-title\"><span><span>${c[1]}</span><span> ( ${c[2]} ) </span></span></h5>" +
            "<table><tr><td>Class Number</td><td>Faculty</td><td>Slot</td><td>Venue</td></tr><tr><td>${c[0]}</td><td>${c[3]}</td><td>${c[4]}</td><td>${c[5]}</td></tr></table>" +
            "<table><tr><td>Description</td><td>Action</td></tr>$action</table></div></td></tr>"
    } + "</table>"

    private val bonafideKinds = listOf(
        "1" to "PASSPORT", "2" to "VISA", "3" to "VISA (FOR VACATION)", "6" to "DRIVING LICENCE", "7" to "BUS PASS", "8" to "SCHOLARSHIP",
        "9" to "EDUCATION LOAN", "10" to "INDUSTRIAL INTERNSHIP (4 Weeks)", "13" to "HIGHER STUDIES", "14" to "MEDIUM OF INSTRUCTION",
        "17" to "CONFERENCE (WITH IN INDIA)", "26" to "BANK ACCOUNT OPENING", "27" to "PLACEMENT", "52" to "CHARACTER CERTIFICATE",
    )

    /** The bonafide page, and with [code] the fields that kind asks for, as VTOP's OnChange renders them. */
    private fun bonafide(code: String?): String {
        val options = "<option value=\"\">-- Choose Service --</option>" +
            bonafideKinds.joinToString("") { (v, l) -> "<option value=\"$v\"${if (v == code) " selected" else ""}>$l</option>" }
        fun row(label: String, name: String) = "<tr><td>$label <span>*</span></td><td><input type=\"text\" name=\"$name\" id=\"$name\"/></td></tr>"
        val rows = when (code) {
            null -> ""
            "3" -> row("From Date", "fromDate") + row("To Date", "toDate") + row("Enter Visa Place", "visaPlace")
            "17" -> row("From Date", "fromDate") + row("To Date", "toDate") + row("Name of the Conference / Event", "conferenceName") + row("Name of the Company/organization/institution/Organized by", "companyName")
            else -> ""
        } + if (code != null) "<tr><td>Description (about certificate which you are applying..) <span>*</span></td><td><textarea name=\"description\" id=\"description\"></textarea></td></tr>" else ""
        return "<div id=\"main-section\"><form id=\"studentBonafide\" name=\"studentBonafide\"><input type=\"hidden\" name=\"_csrf\" value=\"DEMO-CSRF\"/>" +
            "<label>Select Service *</label><select name=\"certificateCode\" id=\"certificateCode\" onchange=\"bonafideStudentOnChange(this.value);\">$options</select>" +
            (if (code != null) "<table>$rows<tr><td><button type=\"button\" name=\"action\">Apply</button></td></tr></table>" else "") + "</form></div>" +
            "<script>function bonafideStudentOnChange(code) { if (parseInt(code) == 3 || parseInt(code) == 16 || parseInt(code) == 43) { " +
            "document.getElementById(\"fromDate\").value = \"20-Dec-2026\"; document.getElementById(\"toDate\").value = \"03-Jan-2027\"; } }</script>"
    }

    @Volatile
    private var slip: Map<String, String>? = null
    private val venues = mapOf("TT" to "Technology Tower", "SJT" to "Silver Jubilee Tower", "LIB" to "Central Library")

    private const val NIGHT_SLIP_FORM = """
<div id="DataBlock"><form id="LateHourRequestForm" name="LateHourRequestForm">
  <input type="hidden" name="studentId" value="10077">
  <div class="form-group"><label for="venue">Venue *</label><div><select name="venue" id="venue"><option value="">--Select--</option>
    <option value="TT">Technology Tower</option><option value="SJT">Silver Jubilee Tower</option><option value="LIB">Central Library</option></select></div></div>
  <div class="form-group"><label for="eventType">Event Type *</label><div><select name="eventType" id="eventType"><option value="">--Select--</option>
    <option value="LAB">LAB</option><option value="EVENT">EVENT</option></select></div></div>
  <div class="form-group"><label for="purpose">Details *</label><div><input type="text" name="purpose" id="purpose" maxlength="100" required></div></div>
  <div class="form-group"><label for="facultyId">Applied To *</label><div><select name="facultyId" id="facultyId"><option value="">--Select--</option><option value="10021">RAMAN K</option></select></div></div>
  <div class="form-group"><label for="fromDate">From Date *</label><div><input type="date" name="fromDate" id="fromDate"></div></div>
  <div class="form-group"><label for="toDate">To Date *</label><div><input type="date" name="toDate" id="toDate"></div></div>
  <div class="form-group"><label for="fromTime">From Time *</label><div><input type="time" name="fromTime" id="fromTime"></div></div>
  <div class="form-group"><label for="toTime">To Time *</label><div><input type="time" name="toTime" id="toTime"></div></div>
  <button type="button" name="submitRequest" data-isprimarycontrol="true" data-formname="LateHourRequestForm" data-controlname="submitRequest"
    data-urltext="hostels/late/hour/student/request/3" onclick="requestUpdate(this);">Submit</button>
</form><p style="display: none;" id="DataBlockMessage"></p></div>"""

    /** id, name, designation, school, email, cabin. */
    private val faculty = listOf(
        listOf("10001", "Dr. ASHA RAMAN", "Associate Professor Grade 1", "School of Advanced Sciences", "asha.raman@example.edu", "SJT 411 A32"),
        listOf("10021", "Mr. RAMAN K", "Assistant Professor (Junior)", "School of Computer Science and Engineering", "raman.k@example.edu", "PRP 218"),
        listOf("10022", "Dr. PRIYA RAMANATHAN", "Professor Grade 1", "School of Electronics Engineering", "priya.r@example.edu", "TT 431"),
        listOf("10023", "Ms. KAVYA MENON", "Assistant Professor Sr. Grade 1", "School of Social Sciences and Languages", "kavya.menon@example.edu", ""),
    )

    private fun facultySearch(q: String): String {
        val hits = faculty.filter { it[1].contains(q, ignoreCase = true) }
        if (hits.isEmpty()) return "<div><strong>Faculty Information</strong><span>No records found</span></div>"
        return "<table><tr><td>Name of the Faculty</td><td>Designation</td><td>School / Centre</td><td>Action</td></tr>" +
            hits.joinToString("") { "<tr><td>${it[1]}</td><td>${it[2]}</td><td>${it[3]}</td><td><button onclick=\"getEmployeeIdNo(&quot;${it[0]}&quot;);\"></button></td></tr>" } +
            "</table>"
    }
}
