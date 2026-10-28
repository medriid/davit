package dev.medriid.davit.vtop

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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

    /** Across all semesters, so no semester id. */
    fun gradeHistory(): GradeHistory = timed("grades") {
        Parsers.gradeHistory(authed("/examinations/examGradeView/StudentGradeHistory", mapOf("verifyMenu" to "true")))
    }

    /** Raw HTML of the last authenticated page, for the opt-in debug dump. */
    var lastPage: Pair<String, String>? = null
        private set

    // ---- plumbing ------------------------------------------------------------------

    /** An authenticated POST. Throws [SessionExpired] if VTOP bounced us to a login page. */
    private fun authed(path: String, extra: Map<String, String>): String {
        val s = session ?: throw SessionExpired()
        val (html, url) = post(path, base(s) + extra)
        if (Parsers.isLoggedOutPage(html, url)) throw SessionExpired()
        // Keep the CSRF token current if the page carries a newer one.
        Parsers.csrfOf(org.jsoup.Jsoup.parse(html))?.let { if (it != s.csrf) session = s.copy(csrf = it) }
        lastPage = path to html
        return html
    }

    private fun base(s: VtopSession) = mapOf("_csrf" to s.csrf, "authorizedID" to s.authorizedId, "x" to utcNow())

    private fun get(path: String): Response = call(Request.Builder().url(url(path)).get().build())

    private fun post(path: String, form: Map<String, String>, referer: String = "/content"): Pair<String, String> {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder()
            .url(url(path))
            .post(body)
            .header("Referer", url(referer).toString())
            .header("X-Requested-With", "XMLHttpRequest")
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
 * Read-only stance (docs/04): only VTOP's host, and never the upload endpoints.
 * Anything else fails before it leaves the phone.
 */
private class ReadOnlyGuard(private val host: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.host != host) throw IOException("blocked: daVIT only talks to $host")
        if (DENY.any { url.encodedPath.endsWith(it) }) throw IOException("blocked: write endpoint")
        return chain.proceed(chain.request())
    }

    companion object {
        val DENY = listOf(
            "/examinations/processDigitalAssignmentUpload",
            "/examinations/doDAssignmentUploadMethod",
        )
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
