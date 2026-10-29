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
import okio.ForwardingSink
import okio.buffer
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
) {
    private val solver: CaptchaSolver by lazy { solver ?: CaptchaSolver.load() }
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val cookies = MemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .addInterceptor(ReadOnlyGuard(base.host))
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
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.fields.forEach { (k, v) -> addFormDataPart(k, if (k == "_csrf") s.csrf else v) }
            addFormDataPart(form.fileField, fileName, Counting(bytes.toRequestBody(mimeType.toMediaTypeOrNull()), onProgress))
        }.build()
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

    /** Raw HTML of the last authenticated page, for the opt-in debug dump. */
    var lastPage: Pair<String, String>? = null
        private set

    // ---- plumbing ------------------------------------------------------------------

    /** An authenticated POST. Throws [SessionExpired] if VTOP bounced us to a login page. */
    private fun authed(path: String, extra: Map<String, String>, permit: WritePermit? = null): String {
        val s = session ?: throw SessionExpired()
        val (html, url) = post(path, base(s) + extra, permit = permit)
        if (Parsers.isLoggedOutPage(html, url)) throw SessionExpired()
        // Keep the CSRF token current if the page carries a newer one.
        Parsers.csrfOf(org.jsoup.Jsoup.parse(html))?.let { if (it != s.csrf) session = s.copy(csrf = it) }
        lastPage = path to html
        return html
    }

    /** A multipart POST (uploads); returns the page and VTOP's `error`/`message` headers. */
    private fun multipart(path: String, body: RequestBody, permit: WritePermit): Pair<String, Map<String, String>> {
        val req = Request.Builder()
            .url(url(path))
            .post(body)
            .header("Referer", url("/content").toString())
            .header("X-Requested-With", "XMLHttpRequest")
            .tag(WritePermit::class.java, permit)
            .build()
        return call(req).use { resp ->
            val html = resp.body?.string().orEmpty()
            if (Parsers.isLoggedOutPage(html, resp.request.url.toString())) throw SessionExpired()
            Parsers.csrfOf(org.jsoup.Jsoup.parse(html))?.let { t -> session?.let { if (it.csrf != t) session = it.copy(csrf = t) } }
            lastPage = path to html
            html to listOfNotNull(resp.header("error")?.let { "error" to it }, resp.header("message")?.let { "message" to it }).toMap()
        }
    }

    private fun base(s: VtopSession) = mapOf("_csrf" to s.csrf, "authorizedID" to s.authorizedId, "x" to utcNow())

    private fun get(path: String): Response = call(Request.Builder().url(url(path)).get().build())

    private fun post(path: String, form: Map<String, String>, referer: String = "/content", permit: WritePermit? = null): Pair<String, String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder()
            .url(url(path))
            .post(body)
            .header("Referer", url(referer).toString())
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (permit != null) tag(WritePermit::class.java, permit) }
            .build()
        return call(req).use { (it.body?.string().orEmpty()) to it.request.url.toString() }
    }

    private fun call(req: Request): Response = try {
        http.newCall(req.newBuilder().header("User-Agent", USER_AGENT).build()).execute()
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
 * hostel leave) only with a [WritePermit] from a confirmed tap; the ones it never makes
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
        )

        /** Never, permit or not: money, passwords, registration and VTOP's other forms. */
        val NEVER = listOf(
            "otpVerfication", "otpVerification", "paymentGateway", "doPayment", "makePayment", "onlinePayment", "payFee",
            "wallet", "password", "passwd", "BonafideSave", "saveAcademicsCourseFeedback", "PaperSeeingSubmit",
            "messComplaint", "late/hour/student/request/3", "courseRegistration",
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

/** Reports bytes as they're written, for the upload progress bar. */
private class Counting(private val inner: RequestBody, private val onProgress: (Long, Long) -> Unit) : RequestBody() {
    override fun contentType(): MediaType? = inner.contentType()
    override fun contentLength(): Long = inner.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        val counting = object : ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent, total)
            }
        }.buffer()
        inner.writeTo(counting)
        counting.flush()
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
