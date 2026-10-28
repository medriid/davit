package dev.medriid.davit.vtop

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The login state machine against a scripted VTOP (pages shaped like vtop-desktop's
 * mock-vtop.js). The captcha image is a stub; the script decides whether each guess
 * "was right", so this tests the retry and stop rules, not the solver.
 */
class LoginFlowTest {
    private val server = MockWebServer()
    private val stubImage = ImageDecoder { Pixels(200, 40, IntArray(200 * 40) { -1 }) }

    /** Outcome of each POST /login in order: "captcha", "creds" or "ok". */
    private var script = ArrayDeque<String>()
    private var loginPosts = 0
    private var validSession: String? = null
    /** prelogin/setup serves Google's reCAPTCHA alone this many more times. */
    private var googleOnly = 0
    private var setups = 0
    /** Put a (hidden) reCAPTCHA widget next to the image captcha, as live VTOP can. */
    private var widgetAlongside = false
    private val fixtures = mapOf(
        "/vtop/processViewTimeTable" to res("timetable.html"),
        "/vtop/processViewStudentAttendance" to res("attendance.html"),
        "/vtop/academics/common/StudentAttendance" to res("attendance.html"),
    )

    private fun res(name: String) = javaClass.getResource("/html/$name")!!.readText()

    private val loginPage = { error: String ->
        """<html><body>${if (error.isNotEmpty()) "<div class=\"alert alert-danger\">$error</div>" else ""}
        <form id="vtopLoginForm"><input type="hidden" name="_csrf" value="login-csrf">
        ${if (widgetAlongside) "<div id=\"recaptcha\" class=\"g-recaptcha\" style=\"display:none\"></div>" else ""}
        <div id="captchaBlock"><img src="data:image/png;base64,AAAA"></div></form></body></html>"""
    }
    private val googlePage = """<html><body><form id="vtopLoginForm"><input type="hidden" name="_csrf" value="login-csrf">
        <div id="captchaBlock"></div><div id="recaptcha" class="g-recaptcha" data-sitekey="x"></div></form></body></html>"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val cookie = request.getHeader("Cookie").orEmpty()
                return when {
                    path == "/vtop/open/page" -> page(
                        """<form id="stdForm"><input name="_csrf" value="open-csrf"></form>""",
                    ).addHeader("Set-Cookie", "JSESSIONID=pre; Path=/")
                    path == "/vtop/prelogin/setup" -> {
                        setups++
                        if (googleOnly > 0) {
                            googleOnly--
                            page(googlePage)
                        } else {
                            page(loginPage(""))
                        }
                    }
                    path == "/vtop/login" -> {
                        loginPosts++
                        when (script.removeFirst()) {
                            "captcha" -> page(loginPage("Invalid Captcha"))
                            "creds" -> page(loginPage("Invalid User Id / Password"))
                            else -> {
                                validSession = "s${loginPosts}"
                                MockResponse().setResponseCode(302)
                                    .addHeader("Location", "/vtop/content")
                                    .addHeader("Set-Cookie", "JSESSIONID=$validSession; Path=/")
                            }
                        }
                    }
                    path == "/vtop/content" -> page(
                        """<input type="hidden" id="authorizedIDX" value="REGNO"><input name="_csrf" value="content-csrf">""",
                    )
                    path in fixtures -> {
                        if (validSession != null && "JSESSIONID=$validSession" in cookie) page(fixtures.getValue(path))
                        else page(loginPage(""))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun page(html: String) = MockResponse().setBody(html).addHeader("Content-Type", "text/html")

    private fun client() = VtopClient(stubImage, CaptchaSolver.constant(), server.url("/vtop").toString(), pause = {})

    @Test
    fun wrongCaptchaIsRetriedThenSucceeds() {
        script = ArrayDeque(listOf("captcha", "captcha", "ok"))
        val c = client()
        val s = c.login("REGNO", "pw")
        assertEquals(VtopSession("content-csrf", "REGNO"), s)
        assertEquals(3, loginPosts)
        assertEquals(3, c.timetable("VL1").size)
    }

    @Test
    fun badPasswordStopsAtOnce() {
        script = ArrayDeque(listOf("creds", "ok"))
        try {
            client().login("REGNO", "wrong")
            fail("expected BadCredentials")
        } catch (_: BadCredentials) {
        }
        assertEquals(1, loginPosts)
    }

    @Test
    fun captchaBudgetRunsOut() {
        script = ArrayDeque(List(10) { "captcha" })
        try {
            client().login("REGNO", "pw", maxAttempts = 4)
            fail("expected CaptchaFailed")
        } catch (_: CaptchaFailed) {
        }
        assertEquals(4, loginPosts)
    }

    @Test
    fun savedSessionSkipsLoginUntilItExpires() {
        script = ArrayDeque(listOf("ok"))
        val saved = client().run {
            login("REGNO", "pw")
            exportSession()
        }
        assertNotNull(saved)

        // A new client (an app restart) restores cookies + tokens and goes straight to data.
        val again = client().apply { restoreSession(saved!!) }
        assertEquals(3, again.attendance("VL1").size)
        assertEquals(1, loginPosts)

        // VTOP forgets the session: the next call must say so instead of returning nothing.
        validSession = null
        try {
            again.timetable("VL1")
            fail("expected SessionExpired")
        } catch (_: SessionExpired) {
        }
        assertTrue(again.timings.any { it.first == "attendance" })
    }

    @Test
    fun recaptchaWidgetNextToImageCaptchaIsIgnored() {
        widgetAlongside = true
        script = ArrayDeque(listOf("ok"))
        client().login("REGNO", "pw")
        assertEquals(1, setups)
        assertEquals(1, loginPosts)
    }

    @Test
    fun googleOnlyIsDodgedThenSolved() {
        googleOnly = 2
        script = ArrayDeque(listOf("ok"))
        val seen = mutableListOf<VtopClient.Progress>()
        client().login("REGNO", "pw", onProgress = { seen += it })
        assertEquals(3, setups)
        assertEquals(listOf(VtopClient.Progress.Dodging(1, 10), VtopClient.Progress.Dodging(2, 10)), seen.filterIsInstance<VtopClient.Progress.Dodging>())
    }

    @Test
    fun googleEveryTimeGivesUpAfterTheDodgeBudget() {
        googleOnly = 100
        try {
            client().login("REGNO", "pw", maxDodges = 3)
            fail("expected RecaptchaRequired")
        } catch (_: RecaptchaRequired) {
        }
        assertEquals(4, setups)
        assertEquals(0, loginPosts)
    }
}
