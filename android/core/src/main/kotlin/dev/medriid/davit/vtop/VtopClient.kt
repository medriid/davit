package dev.medriid.davit.vtop

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Talks to VTOP over plain HTTP, the way `web/vtop_client.py` does: no WebView, no page
 * polling. Blocking calls; run them off the main thread.
 *
 * The session (cookies + CSRF + reg number) can be exported and restored, so most syncs
 * skip the captcha login entirely.
 */
class VtopClient(
    private val decoder: ImageDecoder,
    /** Null loads the bundled model on first login. */
    solver: CaptchaSolver? = null,
    baseUrl: String = DEFAULT_BASE,
    /** How [login] waits between reCAPTCHA dodges; tests pass a no-op. */
    private val pause: (Long) -> Unit = Thread::sleep,
    /** Debug builds: each request's stages with timings (paths and byte counts only, never values). */
    private val netLog: ((String) -> Unit)? = null,
) {
    private val solver: CaptchaSolver by lazy { solver ?: CaptchaSolver.load() }
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val cookies = MemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .addInterceptor(ReadOnlyGuard(base.host))
        .apply { netLog?.let { eventListenerFactory { call -> NetLog(call.request().url.encodedPath, it) } } }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var session: VtopSession? = null
        private set

    /** Stage name → milliseconds, for the timing log. Never holds tokens or values. */
    val timings = mutableListOf<Pair<String, Long>>()

    // ---- session persistence -------------------------------------------------------

    /** Cookies + tokens as an opaque string; the app encrypts it before storing. */
    fun exportSession(): String? {
        val s = session ?: return null
        return (listOf(s.csrf, s.authorizedId) + cookies.export()).joinToString("\n")
    }

    fun restoreSession(saved: String) {
        val lines = saved.split('\n')
        if (lines.size < 2) return
        session = VtopSession(lines[0], lines[1])
        cookies.import(lines.drop(2), base)
    }

    fun clearSession() {
        session = null
        cookies.clear()
    }

    // ---- login ---------------------------------------------------------------------

    sealed class Progress {
        data object Connecting : Progress()
        data class Captcha(val attempt: Int) : Progress()
        data object Verifying : Progress()
        /** VTOP showed Google's reCAPTCHA; going round again for the image one. */
        data class Dodging(val attempt: Int, val of: Int) : Progress()
    }

    /**
     * Log in with the captcha solved on the device. Retries only on a wrong or unreadable
     * captcha; a rejected password stops at once so we never risk locking the account.
     */
    fun login(
        username: String,
        password: String,
        maxAttempts: Int = 8,
        maxDodges: Int = 10,
        onProgress: (Progress) -> Unit = {},
    ): VtopSession = timed("login") {
        clearSession()
        onProgress(Progress.Connecting)
        val newPage = { freshLoginPage(maxDodges, onProgress) }
        var page = newPage()
        for (attempt in 1..maxAttempts) {
            onProgress(Progress.Captcha(attempt))
            var text = solveDataUri(page.captchaDataUri)
            // An unreadable guess costs one small request for a new captcha, not a new page walk.
            var swaps = 0
            while (!solver.isConfident(text) && swaps < 3) {
                val block = get("/get/new/captcha").use { it.body?.string().orEmpty() }
                val uri = Parsers.captchaBlock(block) ?: break
                page = page.copy(captchaDataUri = uri)
                text = solveDataUri(uri)
                swaps++
            }
            if (!solver.isConfident(text)) {
                page = newPage()
                continue
            }
            onProgress(Progress.Verifying)
            val (html, url) = post(
                "/login",
                mapOf("_csrf" to page.csrf, "username" to username.trim(), "password" to password, "captchaStr" to text),
                referer = "/login",
            )
            when (Parsers.loginOutcome(html, url)) {
                Parsers.LoginOutcome.Success -> {
                    val s = Parsers.session(html) ?: throw UnexpectedPage("Signed in, but couldn't read the session.")
                    session = s
                    return@timed s
                }
                Parsers.LoginOutcome.BadCredentials -> throw BadCredentials()
                Parsers.LoginOutcome.WrongCaptcha, Parsers.LoginOutcome.Unknown -> {
                    // VTOP re-renders the login page on a wrong captcha; reuse it if it parses.
                    page = runCatching { Parsers.loginPage(html) }.getOrElse { newPage() }
                }
            }
        }
        throw CaptchaFailed(maxAttempts)
    }

    /**
     * The reCAPTCHA dodge: VTOP picks the captcha per visit, so when it serves only
     * Google's, walk in again from /open/page after 1 s, 1.5 s, 2 s... and hope for the
     * image one. Past [maxDodges] it's [RecaptchaRequired] for real.
     */
    private fun freshLoginPage(maxDodges: Int, onProgress: (Progress) -> Unit): Parsers.LoginPage {
        var dodges = 0
        while (true) {
            try {
                return walkToLoginPage()
            } catch (e: RecaptchaRequired) {
                if (++dodges > maxDodges) throw e
                onProgress(Progress.Dodging(dodges, maxDodges))
                pause(1000L + dodges * 500L)
            }
        }
    }

    private fun walkToLoginPage(): Parsers.LoginPage {
        val open = get("/open/page").use { it.body?.string().orEmpty() }
        val csrf = Parsers.openPageCsrf(open) ?: throw UnexpectedPage("Couldn't read VTOP's start page.")
        val (html, _) = post("/prelogin/setup", mapOf("_csrf" to csrf, "flag" to "VTOP"), referer = "/open/page")
        return Parsers.loginPage(html)
    }

    private fun solveDataUri(uri: String): String {
        if (uri.isEmpty()) return ""
        val bytes = Base64.getDecoder().decode(uri.substringAfter(','))
        val px = decoder.decode(bytes) ?: return ""
        return solver.solve(px)
    }

    fun logout() {
        val s = session ?: return
        runCatching { post("/logout", base(s)) }
        clearSession()
    }

    // ---- data ----------------------------------------------------------------------

    /** Semester dropdown (also warms up the attendance menu, as VTOP requires). */
    fun semesters(): List<Semester> = timed("semesters") {
        Parsers.semesters(authed("/academics/common/StudentAttendance", mapOf("verifyMenu" to "true")))
    }

    fun attendance(semesterId: String): List<AttendanceRow> = timed("attendance") {
        var rows = Parsers.attendance(authed("/processViewStudentAttendance", mapOf("semesterSubId" to semesterId)))
        if (rows.isEmpty()) {
            // Fallback endpoint for when the direct call comes back empty.
            rows = Parsers.attendance(
                authed("/academics/common/StudentAttendance", mapOf("semesterSubId" to semesterId, "verifyMenu" to "true")),
            )
        }
        rows
    }

    /** Day-by-day history for one course, using the arguments from its summary row's link. */
    fun attendanceDetail(row: AttendanceRow, semesterId: String): List<AttendanceDay> = timed("history") {
        val k = row.detail
        Parsers.attendanceDetail(
            authed(
                "/processViewAttendanceDetail",
                mapOf(
                    "semesterSubId" to k.semesterId.ifEmpty { semesterId },
                    "registerNumber" to k.regNo,
                    "courseId" to k.courseId,
                    "courseType" to k.courseType,
                ),
            ),
        )
    }

    fun timetable(semesterId: String): List<TimetableCourse> = timed("timetable") {
        Parsers.timetable(authed("/processViewTimeTable", mapOf("semesterSubId" to semesterId)))
    }

    fun marks(semesterId: String): List<CourseMarks> = timed("marks") {
        Parsers.marks(authed("/examinations/doStudentMarkView", mapOf("semesterSubId" to semesterId)))
    }

    fun exams(semesterId: String): List<Exam> = timed("exams") {
        Parsers.exams(authed("/examinations/doSearchExamScheduleForStudent", mapOf("semesterSubId" to semesterId)))
    }

    /**
     * The course list, then each course's detail page (one request each, in order). A
     * course whose detail fails keeps an empty list rather than failing the rest.
     */
    fun assignments(semesterId: String): List<CourseAssignments> = timed("assignments") {
        val courses = Parsers.assignmentCourses(authed("/examinations/doDigitalAssignment", mapOf("semesterSubId" to semesterId)))
        courses.map { c ->
            val items = try {
                Parsers.assignments(authed("/examinations/processDigitalAssignment", mapOf("classId" to c.classId)))
            } catch (e: SessionExpired) {
                throw e
            } catch (_: VtopException) {
                emptyList()
            }
            CourseAssignments(c.classId, c.courseCode, c.courseName, c.courseType, items)
        }
    }

    fun profile(): Profile = timed("profile") {
        Parsers.profile(authed("/studentsRecord/StudentProfileAllView", mapOf("verifyMenu" to "true")))
    }

    /** Night slips and leave, active and past. Each page needs its menu warmed up first. */
    fun hostelRequests(): HostelRequests = timed("hostel") {
        authed("/hostels/late/hour/student/request/1", mapOf("verifyMenu" to "true"))
        val activeSlips = Parsers.nightSlips(authed("/hostels/late/hour/student/request/9", mapOf("form" to "", "control" to "status")))
        val pastSlips = Parsers.nightSlips(authed("/hostels/late/hour/student/request/11", mapOf("form" to "", "control" to "history")))
        authed("/hostels/student/leave/1", mapOf("verifyMenu" to "true"))
        val activeLeave = Parsers.leaves(authed("/hostels/student/leave/4", mapOf("form" to "", "control" to "status")), "LeaveAppliedTable")
        val pastLeave = Parsers.leaves(authed("/hostels/student/leave/6", mapOf("form" to "", "control" to "history")), "LeaveHistoryTable")
        HostelRequests(activeSlips, pastSlips, activeLeave, pastLeave)
    }

    /** The class groups VTOP keeps separate calendars for this semester. */
    fun classGroups(semesterId: String): List<ClassGroup> = timed("class groups") {
        authed("/academics/common/CalendarPreview", mapOf("verifyMenu" to "true"))
        Parsers.classGroups(authed("/getDateForSemesterPreview", mapOf("paramReturnId" to "getDateForSemesterPreview", "semSubId" to semesterId)))
    }

    /**
     * The academic calendar for [group], month by month. [only] limits it to some months
     * (by "MMM-yyyy" suffix of the calDate, e.g. "OCT-2026"); null fetches them all.
     */
    fun calendar(semesterId: String, group: String, only: Set<String>? = null): List<CalendarCell> = timed("calendar") {
        val months = Parsers.calendarMonths(
            authed("/getListForSemester", mapOf("paramReturnId" to "getListForSemester", "semSubId" to semesterId, "classGroupId" to group)),
        ).filter { only == null || it.uppercase().substringAfter('-') in only }
        months.flatMap { m ->
            Parsers.calendarMonth(authed("/processViewCalendar", mapOf("calDate" to m, "semSubId" to semesterId, "classGroupId" to group)), m)
        }
    }

    /** One faculty member's card; null if VTOP doesn't know the id. */
    fun faculty(empId: String): FacultyInfo? = timed("faculty") {
        Parsers.faculty(authed("/hrms/EmployeeSearch1ForStudent", mapOf("empId" to empId)), empId)
    }

    /**
     * Any teacher by name, as VTOP's Faculty Info search does: at least three letters (VTOP
     * takes letters and spaces only). Open one with [faculty].
     */
    fun searchFaculty(name: String): List<FacultyHit> = timed("faculty search") {
        val q = name.filter { it.isLetter() || it == ' ' }.trim().replace(Regex(" +"), " ")
        if (q.count { it.isLetter() } < 3) throw Refused("Type at least three letters of the name.")
        authed("/hrms/employeeSearchForStudent", mapOf("verifyMenu" to "true"))
        Parsers.facultySearch(authed("/hrms/EmployeeSearchForStudent", mapOf("empId" to q.lowercase())))
    }

    /** Across all semesters, so no semester id. */
    fun gradeHistory(): GradeHistory = timed("grades") {
        Parsers.gradeHistory(authed("/examinations/examGradeView/StudentGradeHistory", mapOf("verifyMenu" to "true")))
    }

    // ---- home page feeds (read only) -------------------------------------------------

    /** Attendance remarks per course ("CAT1 - Debarred"), from the home page's course table. */
    fun remarks(): List<CourseRemark> = timed("remarks") {
        Feeds.remarks(authed("/get/dashboard/current/semester/course/details", emptyMap()))
    }

    fun feedbackStatus(): List<FeedbackItem> = timed("feedback") { Feeds.feedbacks(authed("/get/last/five/feedbacks", emptyMap())) }

    fun events(): List<ClubEvent> = timed("events") { Feeds.events(authed("/get/scheduled/events", emptyMap())) }

    /** The proctor's note on the home page; null when there's none. */
    fun proctorNote(): String? = timed("proctor note") {
        val doc = org.jsoup.Jsoup.parse(authed("/get/dashboard/proctor/message", emptyMap()))
        doc.select("script, style").remove()
        Html.clean(doc.text()).takeIf { it.length > 2 && !it.contains("no message", ignoreCase = true) }
    }

    /** Spot-light lives in the home page itself. */
    fun spotlight(): List<SpotlightItem> = timed("spotlight") { Feeds.spotlight(authed("/content", emptyMap())) }

    fun registrationSchedule(): List<RegistrationWindow> = timed("registration") {
        Feeds.registration(authed("/academics/common/studentAllocationView", mapOf("verifyMenu" to "true")))
    }

    // ---- people and records (read only) ---------------------------------------------

    fun proctor(): Person? = timed("proctor") { Feeds.proctor(authed("/proctor/viewProctorDetails", mapOf("verifyMenu" to "true"))) }

    fun hodDean(): List<Person> = timed("hod") { Feeds.hodDean(authed("/hrms/viewHodDeanDetails", mapOf("verifyMenu" to "true"))) }

    fun classMessages(): List<VtopMessage> = timed("class messages") {
        Feeds.messages(authed("/academics/common/StudentClassMessage", mapOf("verifyMenu" to "true")))
    }

    fun proctorMessages(): List<VtopMessage> = timed("proctor messages") {
        Feeds.messages(authed("/proctor/viewMessagesSendByProctor", mapOf("verifyMenu" to "true")))
    }

    /** Gate (biometric) log from [from] on, as VTOP lists it. */
    fun biometric(from: java.time.LocalDate): SimpleTable = timed("biometric") {
        authed("/academics/common/BiometricInfo", mapOf("verifyMenu" to "true"))
        val day = from.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH))
        Feeds.table(authed("/getStudViewBioList", mapOf("fromDate" to day)))
    }

    fun receipts(): List<Receipt> = timed("receipts") { Feeds.receipts(authed("/finance/getStudentReceipts", mapOf("verifyMenu" to "true"))) }

    fun feeLetters(): List<FeeLetter> = timed("fee letters") {
        Feeds.feeLetters(authed("/finance/getStudentFeesIntimation", mapOf("verifyMenu" to "true")))
    }

    /** Library dues, amounts only. The page's pay button is never touched. */
    fun libraryDues(): List<Pair<String, Double?>> = timed("library") {
        Feeds.libraryDues(authed("/finance/libraryPayments", mapOf("verifyMenu" to "true")))
    }

    /** Curriculum categories, then each category's courses. */
    fun curriculum(): Pair<List<CurriculumCategory>, List<CurriculumCourse>> = timed("curriculum") {
        val cats = Feeds.curriculumCategories(authed("/academics/common/Curriculum", mapOf("verifyMenu" to "true")))
        val courses = cats.flatMap { c ->
            Feeds.curriculumCourses(authed("/academics/common/curriculumCategoryView", mapOf("categoryId" to c.id)))
                .map { it.copy(group = it.group.ifEmpty { c.name }) }
        }
        cats to courses.distinctBy { it.group + "|" + it.code }
    }

    fun semesterGrades(semesterId: String): SemesterGrades = timed("semester grades") {
        authed("/examinations/examGradeView/StudentGradeView", mapOf("verifyMenu" to "true"))
        Feeds.semesterGrades(authed("/examinations/examGradeView/doStudentGradeView", mapOf("semesterSubId" to semesterId)), semesterId)
    }

    /** Whether mess change is open, and your mess. Only read: daVIT never books or changes a mess. */
    fun messChange(): MessStatus? = timed("mess") { Feeds.messChange(authed("/hostels/onlineMessChange", mapOf("verifyMenu" to "true"))) }

    /** Semesters the co/extra-curricular page lists, newest first as VTOP orders them. */
    fun activitySemesters(): List<Semester> = timed("activities") {
        Records.semesters(authed("/academics/common/ExtraCurricular", mapOf("verifyMenu" to "true")))
    }

    /** Your co/extra-curricular record for one semester; empty for "No Records Found". Its invoice buttons are never touched. */
    fun activities(semesterId: String): SimpleTable = timed("activities list") {
        Feeds.table(authed("/processViewEca", mapOf("semesterSubId" to semesterId, "regNo" to "undefined")))
    }

    fun qcmSemesters(): List<Semester> = timed("qcm") {
        Records.semesters(authed("/academics/common/QCMStudentLogin", mapOf("verifyMenu" to "true")))
    }

    /** Quality circle meetings for one semester: course, faculty, the class's suggestions and the replies. */
    fun qcm(semesterId: String): SimpleTable = timed("qcm list") {
        Feeds.table(authed("/getStudentLoginForQcm", mapOf("paramReturnId" to "getStudentLoginForQcm", "semSubId" to semesterId)))
    }

    /** Course allocation: the semester it's for and the curriculum categories (value → "UCC - University Core Courses"). */
    fun allocation(): Allocation = timed("allocation") {
        Records.allocation(authed("/academics/common/StudentRegistrationScheduleAllocation", mapOf("verifyMenu" to "true")))
    }

    /** The courses in one curriculum category (code → "CODE - Title"). */
    fun allocationCourses(category: String): List<Pair<String, String>> = timed("allocation courses") {
        Records.options(authed("/academics/common/getCoursesListForCurriculmCategory", mapOf("cccategory" to category)), "courseId")
    }

    /** The classes allotted for one course: `Slot Detail | Venue | Faculty | Course Type`. */
    fun allocationClasses(courseCode: String): SimpleTable = timed("allocation classes") {
        Feeds.table(authed("/academics/common/getCoursesDetailForRegistration", mapOf("courseCode" to courseCode)))
    }

    // ---- course materials (read only) -----------------------------------------------

    fun courseOptions(): List<CourseOption> = timed("course pages") {
        CoursePages.courses(authed("/academics/common/CoursePageConsolidated", mapOf("verifyMenu" to "true")))
    }

    /** Every material on [course]'s page (about half a megabyte for a big course). */
    fun courseMaterials(course: CourseOption): CourseMaterials = timed("materials") {
        CoursePages.materials(
            authed(
                "/academics/CoursePageConsolidated/getCourseDetail",
                mapOf("semester" to course.semesterId, "CourseId" to course.courseId, "CoursType" to course.type),
            ),
        )
    }

    // ---- downloads (read only) ------------------------------------------------------

    fun downloadMaterial(fileId: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/downloadCourseMaterialFacultyPdf", mapOf("fileId" to fileId), sink, onProgress = onProgress)

    fun downloadQuestion(classId: String, code: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/examinations/doDownloadQuestion/", mapOf("code" to code, "classIdNumber" to classId), sink, onProgress = onProgress)

    /** Your own uploaded file. VTOP's form names the fields `code1` and `classId1`. */
    fun downloadSubmission(classId: String, code: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/examinations/downloadSTudentDA/", mapOf("code1" to code, "classId1" to classId), sink, onProgress = onProgress)

    /** A Spot-light file: `vtopDownload('downloadSpotLightViewFile/820')` is a GET with the tokens in the query. */
    fun downloadSpotlight(path: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/" + path.trimStart('/'), emptyMap(), sink, get = true, onProgress = onProgress)

    fun downloadSyllabus(courseCode: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/courseSyllabusDownload1", mapOf("courseCode" to courseCode), sink, onProgress = onProgress)

    fun downloadCurriculum(sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/academics/curriculDown/", mapOf("regNo" to (session?.authorizedId ?: "")), sink, onProgress = onProgress)

    fun downloadFeeLetter(batchId: String, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        download("/finance/downloadStudentFeesIntimationLetter", mapOf("batchId" to batchId, "studentNumber" to ""), sink, onProgress = onProgress)

    /**
     * Streams a file VTOP serves into [sink]. A page instead of a file is a bounce to the
     * login ([SessionExpired]) or VTOP's refusal in its words ([UnexpectedPage]).
     */
    private fun download(
        path: String,
        form: Map<String, String>,
        sink: OutputStream,
        get: Boolean = false,
        onProgress: (Long, Long?) -> Unit,
    ): Download = timed("download") {
        val s = session ?: throw SessionExpired()
        val params = base(s) + form
        val builder = if (get) {
            Request.Builder().url(url(path).newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()).get()
        } else {
            Request.Builder().url(url(path)).post(FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build())
        }
        call(builder.header("Referer", url("/content").toString()).build()).use { resp ->
            val body = resp.body ?: throw UnexpectedPage("VTOP sent nothing back.")
            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            val disposition = resp.header("Content-Disposition")
            if (!resp.isSuccessful || (type == "text/html" && disposition?.contains("attachment", true) != true)) {
                val html = body.string()
                if (Parsers.isLoggedOutPage(html, resp.request.url.toString())) throw SessionExpired()
                throw UnexpectedPage(Feeds.warnings(org.jsoup.Jsoup.parse(html)) ?: "VTOP sent a page instead of the file.")
            }
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            Download(fileNameOf(disposition) ?: path.trimEnd('/').substringAfterLast('/'), type.ifEmpty { "application/octet-stream" }, done)
        }
    }

    // ---- writes: only with a person's consent ---------------------------------------

    /** One course's assignments, fresh (after an upload, say). */
    fun courseAssignments(classId: String): List<Assignment> = timed("assignment") {
        Parsers.assignments(authed("/examinations/processDigitalAssignment", mapOf("classId" to classId)))
    }

    /**
     * Opens VTOP's upload form for one assignment, after walking to it the way the page's
     * own buttons do (the semester's list, then the course). Sends no OTP and changes
     * nothing, but it's part of the upload, so it needs the [consent] too.
     */
    fun openUpload(consent: UserConsent, semesterId: String?, classId: String, code: String): UploadForm = timed("upload form") {
        if (semesterId != null) authed("/examinations/doDigitalAssignment", mapOf("semesterSubId" to semesterId))
        authed("/examinations/processDigitalAssignment", mapOf("classId" to classId))
        val path = "/examinations/processDigitalAssignmentUpload"
        CoursePages.uploadForm(authed(path, mapOf("classId" to classId, "mode" to code), WritePermit(path, consent)), classId, code)
    }

    /**
     * Sends [bytes] through [form], as VTOP's own page does (multipart, every field of the
     * form). Replacing a file comes back as [UploadOutcome.NeedsOtp].
     */
    fun upload(
        consent: UserConsent,
        form: UploadForm,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): UploadOutcome = timed("upload") {
        val s = session ?: throw SessionExpired()
        val path = "/examinations/doDAssignmentUploadMethod"
        val fields = form.fields.map { (k, v) -> k to if (k == "_csrf") s.csrf else v }
        val body = BrowserFormData(fields, form.fileAt, form.fileField, fileName, mimeType, bytes, onProgress)
        val (html, headers) = multipart(path, body, WritePermit(path, consent))
        CoursePages.uploadOutcome(html, form.code, headers["error"], headers["message"])
    }

    /** The emailed code for a replacement that VTOP is holding. */
    fun submitUploadOtp(consent: UserConsent, pending: UploadOutcome.NeedsOtp, otp: String, code: String): UploadOutcome = timed("upload otp") {
        val s = session ?: throw SessionExpired()
        val path = "/examinations/doDAssignmentOtpUpload"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            pending.fields.forEach { (k, v) -> addFormDataPart(k, if (k == "_csrf") s.csrf else v) }
            if (pending.fields.none { it.first == "authorizedID" }) addFormDataPart("authorizedID", s.authorizedId)
            addFormDataPart(pending.otpField, otp.trim())
        }.build()
        val (html, headers) = multipart(path, body, WritePermit(path, consent))
        CoursePages.uploadOutcome(html, code, headers["error"], headers["message"])
    }

    /** Leaves an upload or an OTP page the way VTOP's Cancel does: by reopening the course. The old file stays. */
    fun cancelUpload(classId: String) {
        runCatching { authed("/examinations/processDigitalAssignment", mapOf("classId" to classId)) }
    }

    /** The leave application form, opened the way its tab does. Null if VTOP shows none (not a hosteller). */
    fun leaveForm(): LeaveForm? = timed("leave form") {
        authed("/hostels/student/leave/1", mapOf("verifyMenu" to "true"))
        Feeds.leaveForm(authed("/hostels/student/leave/2", mapOf("apply" to "", "form" to "undefined", "control" to "apply")))
    }

    /** Applies for hostel leave with [form] just read from VTOP. Dates go as the page's date inputs send them. */
    fun applyLeave(consent: UserConsent, form: LeaveForm, request: LeaveRequest): LeaveOutcome = timed("leave apply") {
        val path = "/hostels/student/leave/3"
        val day = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val fields = form.fields.filter { it.first != "_csrf" && it.first != "authorizedID" }.toMap() + mapOf(
            "leaveCode" to request.type,
            "visitingPlace" to request.place.trim(),
            "leaveFromDate" to request.from.toLocalDate().format(day),
            "fromTime" to request.from.toLocalTime().format(clock),
            "leaveToDate" to request.to.toLocalDate().format(day),
            "toTime" to request.to.toLocalTime().format(clock),
            "reason" to request.reason.trim(),
            "form" to "LeaveRequestForm",
            "control" to "submitControl2",
        )
        Feeds.leaveOutcome(authed(path, fields, WritePermit(path, consent)))
    }

    /**
     * The night slip (late hour) form, opened the way its tab does, read as rendered; or,
     * when there's none, VTOP's reason ("Already a request is pending for Approval").
     */
    fun nightSlipForm(): FormPage = timed("night slip form") {
        authed("/hostels/late/hour/student/request/1", mapOf("verifyMenu" to "true"))
        Forms.read(authed("/hostels/late/hour/student/request/2", mapOf("apply" to "", "form" to "undefined", "control" to "apply")))
    }

    /**
     * Applies for a night slip with [values] (field name → value as VTOP's inputs send them),
     * on a fresh read of the form. Only if the form's button still sends to `request/3`; a
     * changed form is refused rather than guessed at.
     */
    fun applyNightSlip(consent: UserConsent, values: Map<String, String>): Reply = timed("night slip apply") {
        val path = "/hostels/late/hour/student/request/3"
        val page = nightSlipForm()
        val form = page.form ?: throw Refused(page.message.ifEmpty { "VTOP isn't taking night slips right now." })
        if (form.control.isEmpty() || !("/" + form.submitTo.trimStart('/')).endsWith(path)) {
            throw Refused("VTOP's night slip form has changed, so daVIT won't send it. Apply on VTOP this time.")
        }
        val fields = form.hidden.filter { it.first != "_csrf" && it.first != "authorizedID" }.toMap() +
            form.fields.associate { it.name to (values[it.name] ?: it.value) } +
            mapOf("form" to form.id, "control" to form.control)
        Forms.reply(authed(path, fields, WritePermit(path, consent)))
    }

    /** VTOP's club and chapter events: what's open for registration, what you registered for, board certificates. */
    fun clubEvents(): ClubEvents = timed("club events") {
        Clubs.page(authed("/event/swf/loadEventRegistration", mapOf("verifyMenu" to "true")))
    }

    /** Certificates from VTOP's General Events page. */
    fun generalCertificates(): List<GeneralCertificate> = timed("event certificates") {
        Clubs.generalCertificates(authed("/events/swf/certificateDownload", mapOf("verifyMenu" to "true")))
    }

    /**
     * Registers you for club event [eventId], the way its Register button does, after a
     * fresh look that it still offers registration (not "Closed" or "Limit Exceeded").
     */
    fun registerEvent(consent: UserConsent, eventId: String): Reply = timed("event register") {
        val listing = clubEvents().upcoming.firstOrNull { it.id == eventId } ?: throw Refused("VTOP no longer lists this event.")
        if (!listing.canRegister) throw Refused(listing.state.ifEmpty { "VTOP isn't taking registrations for this event." })
        val path = "/event/swf/registered/doEventRegistraiton"
        Forms.reply(authed(path, mapOf("eventId" to eventId), WritePermit(path, consent)))
    }

    /** A club event's participation certificate, or its winner's one. */
    fun downloadEventCertificate(eventId: String, winner: Boolean, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Download {
        authed("/event/swf/loadEventRegistration", mapOf("verifyMenu" to "true"))
        val path = if (winner) "/events/swf/doDownloadWinnerCertificate" else "/events/swf/doDownloadCertificate"
        return download(path, mapOf("eventId" to eventId), sink, onProgress = onProgress)
    }

    fun downloadBoardCertificate(c: BoardCertificate, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Download {
        authed("/event/swf/loadEventRegistration", mapOf("verifyMenu" to "true"))
        return download(
            "/events/swf/boardMembersCertificate/doDownloadCertificate",
            mapOf("associationId" to c.associationId, "generatedYear" to c.generatedYear, "roleId" to c.roleId), sink, onProgress = onProgress,
        )
    }

    fun downloadGeneralCertificate(c: GeneralCertificate, sink: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Download {
        authed("/events/swf/certificateDownload", mapOf("verifyMenu" to "true"))
        return download(
            "/events/downloadCertificate",
            mapOf("eventId" to c.eventId, "certificateId" to c.certificateId, "participantId" to c.participantId), sink, onProgress = onProgress,
        )
    }

    /** The semesters Course Feedback 24x7 takes feedback for (VTOP lists only the current one). */
    fun feedbackSemesters(): List<Semester> = timed("course feedback") {
        val form = Forms.read(authed("/academics/common/StudentClassGrievance", mapOf("verifyMenu" to "true")), "courseFeedbackStudentView").form
        form?.fields?.firstOrNull { it.name == "semesterSubId" }?.options?.map { Semester(it.first, it.second) }.orEmpty()
    }

    fun classFeedback(semesterId: String): List<ClassFeedback> = timed("course feedback list") {
        Clubs.classFeedback(authed("/academics/common/processViewFeedbackList", mapOf("semesterSubId" to semesterId)))
    }

    /**
     * Sends [text] as feedback on one class, as its Submit does, after a fresh look that the
     * class still takes it. VTOP doesn't let feedback be changed once sent.
     */
    fun submitClassFeedback(consent: UserConsent, semesterId: String, classId: String, text: String): Reply = timed("course feedback send") {
        val words = text.trim()
        if (words.isEmpty()) throw Refused("Write something first.")
        val c = classFeedback(semesterId).firstOrNull { it.classId == classId } ?: throw Refused("VTOP no longer lists this class.")
        if (!c.open) throw Refused("VTOP already has your feedback for this class.")
        if (words.length > c.maxLength) throw Refused("VTOP takes up to ${c.maxLength} characters.")
        val path = "/academics/common/saveAcademicsCourseFeedback"
        Forms.reply(authed(path, mapOf("semesterSubId" to semesterId, "classId" to classId, "courseFeedbackVal" to words), WritePermit(path, consent)))
    }

    /** Paper seeing / re-evaluation: its choices (semester, service, exam type) as VTOP's form has them. */
    fun paperSeeingForm(): FormPage = timed("paper seeing") {
        Forms.read(authed("/examinations/paperSeeing/PaperSeeing", mapOf("verifyMenu" to "true")), "paperSeeingView")
    }

    /** The courses VTOP offers for [service] ("2" paper seeing, "1" re-evaluation) in a semester, as its form's change does. */
    fun paperSeeingCourses(semesterId: String, service: String, examType: String): PaperSeeingPage = timed("paper seeing courses") {
        val base = paperSeeingForm().form?.hidden.orEmpty().filter { it.first != "_csrf" && it.first != "authorizedID" }.toMap()
        PaperSeeing.page(
            authed("/examinations/paperSeeing/PaperSeeingOnChange", base + mapOf("semesterSubId" to semesterId, "serviceType" to service, "examType" to examType)),
        )
    }

    /**
     * Asks for [chosen] courses (their checkbox values), on a fresh read that still offers
     * them, sending the whole form as VTOP's Proceed does. VTOP raises a fee invoice for it;
     * paying is left to VTOP.
     */
    fun submitPaperSeeing(consent: UserConsent, semesterId: String, service: String, examType: String, chosen: Set<String>): Reply = timed("paper seeing send") {
        if (chosen.isEmpty()) throw Refused("Pick at least one course.")
        val page = paperSeeingCourses(semesterId, service, examType)
        val offered = page.courses.map { it.value }.toSet()
        if (!offered.containsAll(chosen)) throw Refused(page.message.ifEmpty { "VTOP no longer offers one of those courses." })
        val body = FormBody.Builder().apply {
            val s = session ?: throw SessionExpired()
            add("_csrf", s.csrf)
            add("authorizedID", s.authorizedId)
            add("x", utcNow())
            val fields = page.fields.filter { it.first !in setOf("_csrf", "authorizedID", "semesterSubId", "serviceType", "examType") } +
                listOf("semesterSubId" to semesterId, "serviceType" to service, "examType" to examType)
            fields.forEach { (k, v) -> add(k, v) }
            chosen.forEach { add("frontValue", it) }
        }.build()
        val path = "/examinations/paperSeeing/PaperSeeingSubmit"
        val (html, _) = multipart(path, body, WritePermit(path, consent))
        Forms.reply(html)
    }

    /** The kinds of bonafide certificate VTOP offers (value → "PASSPORT"…), read from its form. */
    fun bonafideServices(): List<Pair<String, String>> = timed("bonafide") {
        val page = Forms.read(authed("/others/bonafide/StudentBonafidePageControl", mapOf("verifyMenu" to "true")), "studentBonafide")
        page.form?.fields?.firstOrNull { it.name == "certificateCode" }?.options ?: throw Refused(page.message.ifEmpty { "VTOP didn't show the bonafide form." })
    }

    /**
     * The bonafide form for one kind ([code]), as VTOP renders it when you pick it: the
     * fields that kind needs. Dates VTOP fixes for a kind (vacation visas) come filled in
     * and locked, as its page does.
     */
    fun bonafideForm(code: String): FormPage = timed("bonafide form") {
        val (html, _) = multipart("/others/bonafide/StudentBonafideOnChange", formData(mapOf("certificateCode" to code)), null)
        val page = Forms.read(html, "studentBonafide")
        val form = page.form ?: return@timed page
        // `if (parseInt(code) == 3 || …) { getElementById("fromDate").value = "20-Dec-2026"; … readOnly = true }`
        val fixedFor = Regex("""parseInt\(code\)\s*==\s*(\d+)""").findAll(html).map { it.groupValues[1] }.toSet()
        val fixed = if (code in fixedFor) {
            Regex("""getElementById\("(\w+)"\)\.value\s*=\s*"([^"]*)"""").findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        } else emptyMap()
        page.copy(
            form = form.copy(
                fields = form.fields.map { f ->
                    when {
                        f.name in fixed -> f.copy(value = fixed.getValue(f.name), readOnly = true)
                        // VTOP's own page stops the description at 100 characters.
                        f.name == "description" && f.maxLength == null -> f.copy(maxLength = 100)
                        else -> f
                    }
                },
            ),
        )
    }

    /** Applies for a bonafide of kind [code] with [values], on a fresh read of that kind's form, as VTOP's Apply sends it. */
    fun applyBonafide(consent: UserConsent, code: String, values: Map<String, String>): Reply = timed("bonafide apply") {
        val form = bonafideForm(code).form ?: throw Refused("VTOP didn't open the bonafide form.")
        if (form.fields.none { it.name == "description" }) throw Refused("VTOP's bonafide form has changed, so daVIT won't send it. Apply on VTOP this time.")
        val fields = form.hidden.filter { it.first != "_csrf" && it.first != "authorizedID" } +
            form.fields.map { f -> f.name to if (f.name == "certificateCode") code else if (f.readOnly) f.value else values[f.name] ?: f.value }
        val path = "/others/bonafide/StudentBonafideSave"
        val (html, _) = multipart(path, formData(fields.toMap()), WritePermit(path, consent))
        Forms.reply(html)
    }

    /** [fields] as a browser's FormData, with the session's tokens first. */
    private fun formData(fields: Map<String, String>): RequestBody {
        val s = session ?: throw SessionExpired()
        return MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("_csrf", s.csrf)
            addFormDataPart("authorizedID", s.authorizedId)
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
        }.build()
    }

    /**
     * VTOP's mess complaint page: its notes (two a month, PDF or image up to 5 MB), or a
     * [Refused] when the form isn't there. Only read.
     */
    fun messComplaintNotes(): List<String> = timed("mess complaint") {
        val doc = org.jsoup.Jsoup.parse(authed("/hostels/messComplaintPage", mapOf("verifyMenu" to "true")))
        if (doc.getElementById("messComplaintForm") == null) throw Refused(Forms.message(doc).ifEmpty { "VTOP isn't taking mess complaints right now." })
        doc.select("span:contains(Note)").firstOrNull()?.wholeText().orEmpty()
            .split('\n').map { Html.clean(it).replace(Regex("""^\d+\.\s*"""), "") }
            .filter { it.isNotEmpty() && !it.startsWith("Note", true) }
    }

    /**
     * Sends a mess complaint: [text] (up to 200 characters) and one photo or PDF up to 5 MB,
     * as VTOP's Save does, after opening the page again for a current token.
     */
    fun sendMessComplaint(consent: UserConsent, text: String, fileName: String, mimeType: String, bytes: ByteArray): Reply = timed("mess complaint send") {
        val words = text.trim()
        if (words.isEmpty()) throw Refused("Say what the complaint is.")
        if (words.length > MESS_TEXT) throw Refused("VTOP takes up to $MESS_TEXT characters.")
        if (bytes.isEmpty() || bytes.size > MESS_FILE) throw Refused("The photo or PDF has to be under 5 MB.")
        if (fileName.substringAfterLast('.', "").lowercase() !in setOf("jpg", "jpeg", "png", "pdf")) throw Refused("VTOP takes JPG, PNG or PDF only.")
        messComplaintNotes()
        val s = session ?: throw SessionExpired()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("authorizedID", s.authorizedId)
            .addFormDataPart("complaintData", words)
            .addFormDataPart("complaintFile", fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
            .addFormDataPart("_csrf", s.csrf)
            .build()
        val path = "/hostels/messComplaint/save"
        val (html, _) = multipart(path, body, WritePermit(path, consent))
        Forms.reply(html)
    }

    /** Raw HTML of the last authenticated page, for the opt-in debug dump. */
    var lastPage: Pair<String, String>? = null
        private set

    // ---- plumbing ------------------------------------------------------------------

    /** An authenticated POST. Throws [SessionExpired] if VTOP bounced us to a login page. */
    private fun authed(path: String, extra: Map<String, String>, permit: WritePermit? = null): String {
        val s = session ?: throw SessionExpired()
        val (html, url) = post(path, base(s) + extra, permit = permit, signedIn = true)
        if (Parsers.isLoggedOutPage(html, url)) throw SessionExpired()
        // Keep the CSRF token current if the page carries a newer one.
        Parsers.csrfOf(org.jsoup.Jsoup.parse(html))?.let { if (it != s.csrf) session = s.copy(csrf = it) }
        lastPage = path to html
        return html
    }

    /**
     * A multipart POST (uploads, and the few pages VTOP reads as FormData); returns the page
     * and VTOP's `error`/`message` headers. Writes need their [permit].
     */
    private fun multipart(path: String, body: RequestBody, permit: WritePermit?): Pair<String, Map<String, String>> {
        val req = Request.Builder()
            .url(url(path))
            .post(body)
            .header("Referer", url("/content").toString())
            // As the page's own fetch(FormData) sends it: Origin and Accept, and no X-Requested-With.
            .header("Origin", "${base.scheme}://${base.host}" + if (base.port != HttpUrl.defaultPort(base.scheme)) ":${base.port}" else "")
            .header("Accept", "*/*")
            .apply { if (permit != null) tag(WritePermit::class.java, permit) }
            .build()
        return call(req, slow).use { resp ->
            val html = resp.body?.string().orEmpty()
            if (Parsers.isLoggedOutPage(html, resp.request.url.toString())) throw SessionExpired()
            Parsers.csrfOf(org.jsoup.Jsoup.parse(html))?.let { t -> session?.let { if (it.csrf != t) session = it.copy(csrf = t) } }
            lastPage = path to html
            html to listOfNotNull(resp.header("error")?.let { "error" to it }, resp.header("message")?.let { "message" to it }).toMap()
        }
    }

    private fun base(s: VtopSession) = mapOf("_csrf" to s.csrf, "authorizedID" to s.authorizedId, "x" to utcNow())

    private fun get(path: String): Response = call(Request.Builder().url(url(path)).get().build())

    private fun post(path: String, form: Map<String, String>, referer: String = "/content", permit: WritePermit? = null, signedIn: Boolean = false): Pair<String, String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder()
            .url(url(path))
            .post(body)
            .header("Referer", url(referer).toString())
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (permit != null) tag(WritePermit::class.java, permit) }
            .build()
        return call(req).use {
            // A page behind sign-in answers 404 once VTOP has ended the session (signing in on the
            // website ends the app's), not its sign-in page.
            if (signedIn && it.code == 404) throw SessionExpired()
            (it.body?.string().orEmpty()) to it.request.url.toString()
        }
    }

    /** Uploads: VTOP can take a while to answer once a file is in. */
    private val slow by lazy { http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build() }

    private fun call(req: Request, client: OkHttpClient = http): Response = try {
        client.newCall(req.newBuilder().header("User-Agent", USER_AGENT).build()).execute()
    } catch (e: IOException) {
        throw Offline(e)
    }

    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).build()

    private inline fun <T> timed(stage: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            timings += stage to (System.nanoTime() - t0) / 1_000_000
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://vtop.vit.ac.in/vtop"

        /** VTOP's mess complaint limits: 200 characters, a 5 MB photo or PDF. */
        const val MESS_TEXT = 200
        const val MESS_FILE = 5 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private fun utcNow(): String =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
    }
}

/**
 * Lets one write endpoint through. Only the client's own consent-taking methods make
 * these, each for its own path, so nothing else (background sync included) can write.
 */
internal class WritePermit(private val path: String, val consent: UserConsent) {
    fun allows(requestPath: String) = requestPath.endsWith(path)
}

/**
 * Read-only stance (docs/04): only VTOP's host; the writes daVIT makes (assignment upload,
 * hostel leave, night slips…) only with a [WritePermit] from a confirmed tap; the ones it never makes
 * (payments, passwords, registration) never. Anything else fails before it leaves the phone.
 */
private class ReadOnlyGuard(private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != host) throw IOException("blocked: daVIT only talks to $host")
        val path = url.encodedPath
        if (NEVER.any { path.contains(it, ignoreCase = true) }) throw IOException("blocked: daVIT never does this")
        if (WRITES.any { path.endsWith(it) } && request.tag(WritePermit::class.java)?.allows(path) != true) {
            throw IOException("blocked: write endpoint")
        }
        return chain.proceed(request)
    }

    companion object {
        /** The writes daVIT makes, each only from a confirmed tap. */
        val WRITES = listOf(
            "/examinations/processDigitalAssignmentUpload",
            "/examinations/doDAssignmentUploadMethod",
            "/examinations/doDAssignmentOtpUpload",
            "/hostels/student/leave/3",
            "/hostels/late/hour/student/request/3",
            "/event/swf/registered/doEventRegistraiton",
            "/others/bonafide/StudentBonafideSave",
            "/academics/common/saveAcademicsCourseFeedback",
            "/examinations/paperSeeing/PaperSeeingSubmit",
            "/hostels/messComplaint/save",
        )

        /** Never, permit or not: money, passwords, registration and VTOP's other forms. */
        val NEVER = listOf(
            "otpVerfication", "otpVerification", "paymentGateway", "doPayment", "makePayment", "onlinePayment", "payFee",
            "wallet", "password", "passwd",
            "courseRegistration",
            // Cancelling a night slip; invoices on the co/extra-curricular page.
            "late/hour/student/request/10", "processViewEcaInvoice", "cancleInvoiceGenerateEca",
        )
    }
}

/** `attachment; filename="a b.pdf"` (or unquoted, or RFC 5987 `filename*=`) → "a b.pdf". */
internal fun fileNameOf(disposition: String?): String? {
    if (disposition.isNullOrBlank()) return null
    val name = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        ?.let { runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull() }
        ?: Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
            ?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] }.trim().trim('"') }
        ?: return null
    // Never let a name climb out of the folder it's saved in.
    return name.substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

/**
 * A file upload exactly as a browser sends `new FormData(form)`: fields in page order with the
 * file at its place, a WebKit-style boundary, and no per-part Content-Length lines (OkHttp's
 * multipart adds those, and VTOP stopped reading such an upload partway). The file goes out in
 * flushed pieces, so the write timeout measures a stall rather than the whole file, and
 * [onProgress] moves as it goes.
 */
private class BrowserFormData(
    fields: List<Pair<String, String>>,
    fileAt: Int,
    fileField: String,
    fileName: String,
    mimeType: String,
    private val file: ByteArray,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    private val boundary = "----WebKitFormBoundary" + (1..16).map { BOUNDARY_CHARS.random() }.joinToString("")
    private val head: ByteArray
    private val tail: ByteArray

    init {
        fun part(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"${escape(name)}\"\r\n\r\n$value\r\n"
        val at = fileAt.coerceIn(0, fields.size)
        val fileHead = "--$boundary\r\nContent-Disposition: form-data; name=\"${escape(fileField)}\"; filename=\"${escape(fileName)}\"\r\n" +
            "Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n"
        head = (fields.take(at).joinToString("") { (k, v) -> part(k, v) } + fileHead).toByteArray(Charsets.UTF_8)
        tail = ("\r\n" + fields.drop(at).joinToString("") { (k, v) -> part(k, v) } + "--$boundary--\r\n").toByteArray(Charsets.UTF_8)
    }

    override fun contentType(): MediaType? = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()
    override fun contentLength(): Long = (head.size + file.size + tail.size).toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        var sent = 0
        while (sent < file.size) {
            val n = minOf(PIECE, file.size - sent)
            sink.write(file, sent, n)
            sink.flush()
            sent += n
            onProgress(sent.toLong(), file.size.toLong())
        }
        sink.write(tail)
        sink.flush()
    }

    private companion object {
        const val PIECE = 16 * 1024
        val BOUNDARY_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9')

        /** As browsers escape names in a form-data header. */
        fun escape(s: String) = s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")
    }
}

/** In-memory cookie jar that can be flattened to strings and restored. */
private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store["${it.domain}|${it.path}|${it.name}"] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.values.removeAll { it.expiresAt < now }
        return store.values.filter { it.matches(url) }
    }

    /** Set-Cookie header lines; parsed back with Cookie.parse. */
    @Synchronized
    fun export(): List<String> = store.values.map { it.toString() }

    @Synchronized
    fun import(lines: List<String>, base: HttpUrl) {
        lines.mapNotNull { Cookie.parse(base, it) }.forEach { store["${it.domain}|${it.path}|${it.name}"] = it }
    }

    @Synchronized
    fun clear() = store.clear()
}

/** Where a request spends its time, for [VtopClient]'s debug log: stage, milliseconds since the call began, bytes. */
private class NetLog(private val path: String, private val log: (String) -> Unit) : okhttp3.EventListener() {
    private val t0 = System.nanoTime()
    private fun at(stage: String) = log("$path $stage +${(System.nanoTime() - t0) / 1_000_000}ms")
    override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) = at("connection ${connection.protocol()}")
    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) = at("connect")
    override fun requestHeadersEnd(call: okhttp3.Call, request: Request) = at("headers sent")
    override fun requestBodyStart(call: okhttp3.Call) = at("body start")
    override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) = at("body end $byteCount bytes")
    override fun requestFailed(call: okhttp3.Call, ioe: IOException) = at("request failed ${ioe.javaClass.simpleName}: ${ioe.message}")
    override fun responseHeadersEnd(call: okhttp3.Call, response: Response) = at("response ${response.code}")
    override fun responseFailed(call: okhttp3.Call, ioe: IOException) = at("response failed ${ioe.javaClass.simpleName}: ${ioe.message}")
    override fun callFailed(call: okhttp3.Call, ioe: IOException) = at("call failed ${ioe.javaClass.simpleName}: ${ioe.message}")
}
