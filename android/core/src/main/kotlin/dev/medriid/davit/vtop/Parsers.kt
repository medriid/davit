package dev.medriid.davit.vtop

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * HTML → data for each VTOP page. Selectors follow `web/vtop_client.py` and the proven
 * scrapers (docs/04). Pure functions over strings, so they're tested against fixtures.
 */
object Parsers {

    // ---- login ---------------------------------------------------------------------

    /** `GET /open/page`: the CSRF token on the role chooser (#stdForm). */
    fun openPageCsrf(html: String): String? =
        Jsoup.parse(html).selectFirst("#stdForm input[name=_csrf]")?.attr("value")?.takeIf { it.isNotBlank() }

    data class LoginPage(val csrf: String, val captchaDataUri: String)

    /**
     * The student login form. VTOP's page can carry a Google reCAPTCHA widget next to the
     * image captcha, so it only counts as reCAPTCHA when Google's widget
     * is there and the image captcha isn't: then [RecaptchaRequired]. An empty
     * #captchaBlock gives an empty [LoginPage.captchaDataUri]; login fetches a new one.
     */
    fun loginPage(html: String): LoginPage {
        val doc = Jsoup.parse(html)
        val img = (doc.selectFirst("#captchaBlock img[src^=data:image]") ?: doc.selectFirst("img[src^=data:image]"))
            ?.attr("src").orEmpty()
        if (img.isEmpty() && doc.selectFirst("#recaptcha.g-recaptcha, div.g-recaptcha") != null) throw RecaptchaRequired()
        val csrf = doc.selectFirst("#vtopLoginForm input[name=_csrf]")?.attr("value")
        if (doc.selectFirst("#vtopLoginForm") == null || csrf.isNullOrBlank() ||
            (img.isEmpty() && doc.selectFirst("#captchaBlock") == null)
        ) {
            throw UnexpectedPage("The VTOP login page didn't look as expected.")
        }
        return LoginPage(csrf, img)
    }

    /** A fresh captcha block from `GET /get/new/captcha`. */
    fun captchaBlock(html: String): String? =
        Jsoup.parse(html).selectFirst("img[src^=data:image]")?.attr("src")

    enum class LoginOutcome { Success, WrongCaptcha, BadCredentials, Unknown }

    /** What `POST /login` came back with. A wrong captcha is checked first, as VTOP does. */
    fun loginOutcome(html: String, finalUrl: String): LoginOutcome {
        if ("/vtop/content" in finalUrl || "authorizedidx" in html.lowercase()) return LoginOutcome.Success
        val text = Jsoup.parse(html).text().lowercase()
        if ("invalid captcha" in text || ("captcha" in text && "invalid" in text)) return LoginOutcome.WrongCaptcha
        if (("invalid" in text && ("login" in text || "credential" in text || "user" in text || "password" in text)) ||
            "bad credentials" in text
        ) return LoginOutcome.BadCredentials
        return LoginOutcome.Unknown
    }

    /** The content page after login: reg number (#authorizedIDX) and the session CSRF. */
    fun session(html: String): VtopSession? {
        val doc = Jsoup.parse(html)
        val id = (doc.selectFirst("#authorizedIDX") ?: doc.selectFirst("#authorizedID"))?.attr("value")
        val csrf = csrfOf(doc)
        if (id.isNullOrBlank() || csrf.isNullOrBlank()) return null
        return VtopSession(csrf, id)
    }

    fun csrfOf(doc: Document): String? =
        doc.selectFirst("input[name=_csrf]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=_csrf]")?.attr("content")?.takeIf { it.isNotBlank() }

    /** True when an authenticated request got bounced back to a login page. */
    fun isLoggedOutPage(html: String, finalUrl: String): Boolean {
        val path = finalUrl.substringAfter("://").substringAfter('/')
        if (path.endsWith("vtop/login") || path.contains("open/page") || path.contains("prelogin")) return true
        if (html.length > 200_000) return false
        return html.contains("id=\"vtopLoginForm\"") || html.contains("id=\"stdForm\"")
    }

    // ---- semesters & attendance ----------------------------------------------------

    /** The semester dropdown on the attendance landing page, newest first as VTOP lists them. */
    fun semesters(html: String): List<Semester> {
        val sel = Jsoup.parse(html).selectFirst("#semesterSubId, select[name=semesterSubId]") ?: return emptyList()
        return sel.select("option").mapNotNull { opt ->
            val id = opt.attr("value").trim()
            if (id.isEmpty()) null else Semester(id, clean(opt.text()))
        }
    }

    private val detailCall =
        Regex("""callStudentAttendanceDetailDisplay\('([^']*)','([^']*)','([^']*)','([^']*)'\)""")

    /** `#AttendanceDetailDataTable`: cols [_, _, "code - name - type", _, _, attended, total, %]. */
    fun attendance(html: String): List<AttendanceRow> {
        val table = Jsoup.parse(html).selectFirst("#AttendanceDetailDataTable") ?: return emptyList()
        return table.select("tbody tr").mapNotNull { tr ->
            if (tr.selectFirst("td.dataTables_empty") != null) return@mapNotNull null
            val cells = tr.select("td")
            if (cells.size < 8) return@mapNotNull null
            val parts = clean(cells[2].text()).split(" - ")
            val pctText = (cells[7].selectFirst("span[style]") ?: cells[7]).text()
            val link = cells.last()?.selectFirst("a[onclick]")?.attr("onclick").orEmpty()
            val m = detailCall.find(link)
            AttendanceRow(
                courseCode = parts.getOrElse(0) { "" }.trim(),
                // "code - name - type"; names can contain " - " themselves, the type is last
                courseName = (if (parts.size >= 3) parts.subList(1, parts.size - 1).joinToString(" - ")
                else parts.getOrElse(1) { clean(cells[2].text()) }).trim(),
                classType = (if (parts.size >= 3) parts.last() else "").trim(),
                attended = clean(cells[5].text()).toIntOrNull() ?: 0,
                total = clean(cells[6].text()).toIntOrNull() ?: 0,
                vtopPercent = Regex("""\d+""").find(pctText)?.value?.toIntOrNull(),
                classId = normalizeClassId(m?.groupValues?.get(3).orEmpty()),
            )
        }
    }

    // ---- timetable -----------------------------------------------------------------

    /**
     * `POST /processViewTimeTable`: the course table (the one mentioning "Class Group" and
     * "Course Option"). Only rows that are registered and approved count.
     */
    fun timetable(html: String): List<TimetableCourse> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull {
            val t = it.text()
            "Class Group" in t && "Course Option" in t
        } ?: return emptyList()
        val rows = table.select("tr")
        if (rows.size < 3) return emptyList()
        return rows.subList(1, rows.size - 1).mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 12) return@mapNotNull null
            val status = clean(cells[11].text()).lowercase()
            if ("registered" !in status || "approved" !in status) return@mapNotNull null
            val slotVenue = clean(cells[7].text())
            val nil = "NIL" in slotVenue.uppercase()
            val ps = cells[2].select("p")
            val head = if (ps.isNotEmpty()) clean(ps[0].text()) else clean(cells[2].text())
            val headParts = head.split(" - ")
            val type = if (ps.size > 1) clean(ps[1].text()).trim('(', ')', ' ') else ""
            val classId = classIdOf(row, cells)
            if (classId.isEmpty()) return@mapNotNull null
            TimetableCourse(
                classId = classId,
                courseCode = headParts[0].trim(),
                courseName = headParts.drop(1).joinToString(" - ").trim().ifEmpty { head },
                courseType = type,
                credits = clean(cells[3].text()).split(' ').lastOrNull()?.toDoubleOrNull() ?: 0.0,
                slot = if (nil) "" else slotVenue.substringBefore(" - ").trim(),
                venue = if (nil) "" else slotVenue.substringAfter(" - ", "").trim(),
                faculty = clean(cells[8].text()).substringBefore(" - ").trim(),
            )
        }
    }

    // ---- academics -----------------------------------------------------------------

    /**
     * `POST /examinations/doStudentMarkView`: rows come in pairs, a course row (Sl.No,
     * Class Nbr, Code, Title, Type, …) then a row holding that course's marks table.
     */
    fun marks(html: String): List<CourseMarks> {
        val table = Jsoup.parse(html).selectFirst("#fixedTableContainer table.customTable") ?: return emptyList()
        val rows = table.select("tr.tableContent")
        val out = mutableListOf<CourseMarks>()
        var i = 0
        while (i + 1 < rows.size) {
            val course = rows[i].select("td")
            val detail = rows[i + 1]
            i += 2
            if (course.size < 9) continue
            val items = detail.selectFirst("table.customTable-level1")?.select("tr.tableContent-level1")
                ?.mapNotNull { tr ->
                    val c = tr.select("td")
                    if (c.size < 8) return@mapNotNull null
                    MarkItem(
                        title = clean(c[1].text()),
                        max = number(c[2].text()),
                        weightage = number(c[3].text()),
                        status = clean(c[4].text()),
                        scored = number(c[5].text()),
                        weighted = number(c[6].text()),
                        remark = clean(c[7].text()).takeUnless { it == "-" }.orEmpty(),
                    )
                }.orEmpty()
            out += CourseMarks(
                classId = normalizeClassId(course[1].text()),
                courseCode = clean(course[2].text()),
                courseName = clean(course[3].text()),
                courseType = clean(course[4].text()),
                items = items,
            )
        }
        return out
    }

    /**
     * `POST /examinations/doSearchExamScheduleForStudent`: one table, exam types as
     * `td.panelHead-secondary[colspan]` header rows, then a row per course.
     */
    fun exams(html: String): List<Exam> {
        val box = Jsoup.parse(html).getElementById("fixedTableContainer") ?: return emptyList()
        var type = ""
        return box.select("tr").mapNotNull { tr ->
            if (tr.hasClass("tableHeader")) return@mapNotNull null
            tr.selectFirst("td.panelHead-secondary[colspan]")?.let {
                type = clean(it.text()).uppercase()
                return@mapNotNull null
            }
            val c = tr.select("td")
            if (c.size < 13) return@mapNotNull null
            val code = clean(c[1].text())
            if (code.equals("Course Code", ignoreCase = true)) return@mapNotNull null
            val classId = normalizeClassId(c[4].text()).ifEmpty { return@mapNotNull null }
            val location = clean(c[11].text()).takeUnless { it == "-" }.orEmpty()
            val seatNo = clean(c[12].text()).takeUnless { it == "-" }.orEmpty()
            Exam(
                classId = classId,
                courseCode = code,
                courseName = clean(c[2].text()),
                type = type,
                date = clean(c[6].text()),
                session = clean(c[7].text()),
                time = clean(c[9].text()),
                venue = clean(c[10].text()),
                seat = if (seatNo.isNotEmpty() && location.isNotEmpty()) "$seatNo ($location)" else seatNo.ifEmpty { location },
            )
        }
    }

    /** A course in the assignments list, before its detail page is fetched. */
    data class AssignmentCourse(val classId: String, val courseCode: String, val courseName: String, val courseType: String)

    /**
     * `POST /examinations/doDigitalAssignment`: the table with "Class Nbr", "Course Code"
     * and "Dashboard"; one row per course. The class id is in its own cell, or in the
     * Dashboard button's `myFunction('VL…')`.
     */
    fun assignmentCourses(html: String): List<AssignmentCourse> {
        val table = Jsoup.parse(html).select("table").firstOrNull {
            val t = it.text()
            "Class Nbr" in t && "Course Code" in t && "Dashboard" in t
        } ?: return emptyList()
        return table.select("tr").mapNotNull { tr ->
            val c = tr.select("td")
            if (c.size < 7) return@mapNotNull null
            val code = clean(c[2].text())
            if (code.equals("Course Code", ignoreCase = true)) return@mapNotNull null
            val onclick = c[6].selectFirst("button[onclick]")?.attr("onclick").orEmpty()
            val classId = normalizeClassId(c[1].text()).ifEmpty {
                normalizeClassId(Regex("""myFunction\('([^']+)'\)""").find(onclick)?.groupValues?.get(1).orEmpty())
            }.ifEmpty { return@mapNotNull null }
            AssignmentCourse(classId, code, clean(c[3].text()), clean(c[4].text()))
        }
    }

    /**
     * `POST /examinations/processDigitalAssignment` + classId: the table with "Weightage",
     * "Due Date" and "Last Updated On". Cells: 1 title, 2 max, 3 weightage, 4 due, 6 updated.
     */
    fun assignments(html: String): List<Assignment> {
        val table = Jsoup.parse(html).select("table").firstOrNull {
            val t = it.text()
            "Weightage" in t && "Due Date" in t && "Last Updated On" in t
        } ?: return emptyList()
        return table.select("tr").mapNotNull { tr ->
            val c = tr.select("td")
            if (c.size < 8) return@mapNotNull null
            val title = clean(c[1].text())
            if (title.isEmpty() || title.equals("Title", ignoreCase = true)) return@mapNotNull null
            Assignment(
                title = title,
                max = number(c[2].text()),
                weightage = number(c[3].text()),
                due = clean(c[4].text()),
                updated = clean(c[6].text()).takeUnless { it.isEmpty() || it == "-" },
            )
        }
    }

    /** VIT's grade points. P, W, U and anything unknown don't count towards CGPA. */
    private val gradePoints = mapOf("S" to 10, "A" to 9, "B" to 8, "C" to 7, "D" to 6, "E" to 5, "F" to 0, "N" to 0)

    /**
     * `POST /examinations/examGradeView/StudentGradeHistory`: the "Effective Grades" table,
     * and the CGPA from the summary table when the page has one. Without it the CGPA is
     * computed from the effective grades (credits × points over graded credits).
     */
    fun gradeHistory(html: String): GradeHistory {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")
        val grades = doc.select("table.customTable").firstOrNull { "Effective Grades" in it.text() }
        val entries = grades?.select("tr.tableContent")
            ?.filterNot { it.attr("style").replace(" ", "").contains("display:none") || it.id().startsWith("detailsView_") }
            ?.mapNotNull { tr ->
                val c = tr.select("td")
                if (c.size < 6) return@mapNotNull null
                GradeEntry(
                    courseCode = clean(c[1].text()),
                    courseTitle = clean(c[2].text()),
                    credits = number(c[4].text()) ?: 0.0,
                    grade = clean(c[5].text()).takeUnless { it.isEmpty() || it == "-" },
                    examMonth = if (c.size > 6) clean(c[6].text()) else "",
                )
            }.orEmpty()

        // Summary table: a header row with "CGPA" (and usually "Credits Earned"), values in the next row.
        var cgpa: Double? = null
        var earned: Double? = null
        for (t in tables) {
            val rows = t.select("tr")
            for ((r, row) in rows.withIndex()) {
                val heads = row.select("th, td").map { clean(it.text()).uppercase() }
                val at = heads.indexOf("CGPA")
                if (at < 0 || r + 1 >= rows.size) continue
                val values = rows[r + 1].select("td")
                cgpa = values.getOrNull(at)?.let { number(it.text()) }
                earned = heads.indexOf("CREDITS EARNED").takeIf { it >= 0 }?.let { values.getOrNull(it) }?.let { number(it.text()) }
                if (cgpa != null) break
            }
            if (cgpa != null) break
        }
        if (cgpa == null) {
            val graded = entries.filter { it.credits > 0 && it.grade?.uppercase() in gradePoints }
            val credits = graded.sumOf { it.credits }
            if (credits > 0) cgpa = graded.sumOf { it.credits * gradePoints.getValue(it.grade!!.uppercase()) } / credits
        }
        return GradeHistory(cgpa, earned, entries)
    }

    private fun number(s: String): Double? = clean(s).toDoubleOrNull()

    private fun classIdOf(row: Element, cells: List<Element>): String {
        normalizeClassId(cells[6].text()).let { if (it.isNotEmpty()) return it }
        for (cell in cells) {
            normalizeClassId(cell.text()).let { if (it.isNotEmpty()) return it }
            normalizeClassId(cell.attr("data-classid")).let { if (it.isNotEmpty()) return it }
        }
        return normalizeClassId(row.text())
    }

    /** VTOP class ids look like VL2026270100123: take the longest VL+digits run (≥ 8 chars). */
    fun normalizeClassId(value: String): String {
        val compact = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return Regex("VL[0-9]+").findAll(compact).map { it.value }.maxByOrNull { it.length }
            ?.takeIf { it.length >= 8 }.orEmpty()
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
