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
                detail = m?.groupValues?.let { DetailKey(it[1].trim(), it[2].trim(), it[3].trim(), it[4].trim().uppercase()) } ?: DetailKey(),
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

    /** `POST /processViewAttendanceDetail`: `#StudentAttendanceDetailDataTable`, cells 1 date, 2 slot, 4 status. */
    fun attendanceDetail(html: String): List<AttendanceDay> {
        val table = Jsoup.parse(html).getElementById("StudentAttendanceDetailDataTable") ?: return emptyList()
        return table.select("tbody tr").mapNotNull { tr ->
            if (tr.selectFirst("td.dataTables_empty") != null) return@mapNotNull null
            val c = tr.select("td")
            if (c.size < 5) return@mapNotNull null
            val raw = clean(c[4].text()).lowercase()
            val status = when {
                raw.startsWith("present") -> AttendanceDay.Status.Present
                raw.startsWith("absent") -> AttendanceDay.Status.Absent
                raw == "od" || "duty" in raw -> AttendanceDay.Status.OnDuty
                else -> AttendanceDay.Status.Other
            }
            val dayOrder = DayNames.indexOf(clean(c[3].text()).take(3).uppercase()).takeIf { it >= 0 }?.plus(1)
            AttendanceDay(clean(c[1].text()), clean(c[2].text()), status, dayOrder)
        }
    }

    private val DayNames = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    // ---- academic calendar ---------------------------------------------------------

    /** `getDateForSemesterPreview`: the class-group dropdown. */
    fun classGroups(html: String): List<ClassGroup> =
        Jsoup.parse(html).select("select#classGroupId option").mapNotNull { o ->
            val id = o.attr("value").trim()
            if (id.isEmpty()) null else ClassGroup(id, clean(o.text()))
        }

    /** `getListForSemester`: the month buttons, as `calDate` values ("01-OCT-2026"). */
    fun calendarMonths(html: String): List<String> =
        Regex("""processViewCalendar\('([^']+)'\)""").findAll(Jsoup.parse(html).select("[onclick]").joinToString(" ") { it.attr("onclick") })
            .map { it.groupValues[1] }.distinct().toList()

    /**
     * `processViewCalendar`: one month. The last `.calendar-table` is a Sunday-first grid;
     * each day is `<span>day</span><span>label</span><span>(note)</span>`. [calDate] gives the
     * month when the heading is missing.
     */
    fun calendarMonth(html: String, calDate: String): List<CalendarCell> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table.calendar-table").lastOrNull() ?: return emptyList()
        val month = runCatching {
            java.time.YearMonth.parse(calDate.trim().uppercase().substringAfter('-'), java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive().appendPattern("MMM-yyyy").toFormatter(java.util.Locale.ENGLISH))
        }.getOrNull() ?: return emptyList()
        return table.select("td").mapNotNull { td ->
            val spans = td.select("span")
            val day = spans.firstOrNull()?.let { clean(it.text()).toIntOrNull() } ?: return@mapNotNull null
            if (day !in 1..month.lengthOfMonth()) return@mapNotNull null
            val label = spans.drop(1).map { clean(it.text()) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val note = spans.drop(2).map { clean(it.text()).removePrefix("(").removeSuffix(")").trim() }
                .filter { it.isNotEmpty() }.joinToString(" | ")
            CalendarCell(month.atDay(day), label, note)
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

    // ---- profile and hostel ---------------------------------------------------------

    /**
     * `POST /studentsRecord/StudentProfileAllView`: label/value `<td>` pairs (STUDENT NAME,
     * GENDER), the photo as an inline image, and a "HOSTEL INFORMATION" accordion.
     */
    fun profile(html: String): Profile {
        val doc = Jsoup.parse(html)
        val tds = doc.select("td")
        fun after(label: String): String {
            val i = tds.indexOfFirst { clean(it.text()).equals(label, ignoreCase = true) }
            return if (i >= 0 && i + 1 < tds.size) clean(tds[i + 1].text()) else ""
        }
        val name = after("STUDENT NAME").split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        val photo = (doc.selectFirst("img.img.border.border-primary[src^=data:image/]") ?: doc.selectFirst("img[src^=data:image/]"))
            ?.attr("src")?.takeIf { it.length > 100 }
        val hostelTable = doc.select(".accordion-button").firstOrNull { "HOSTEL INFORMATION" in clean(it.text()).uppercase() }
            ?.closest(".accordion-item")?.selectFirst("table")
        val hostel = hostelTable?.let { t ->
            val cells = t.select("td")
            fun value(label: String): String {
                val i = cells.indexOfFirst { clean(it.text()).equals(label, ignoreCase = true) }
                return if (i >= 0 && i + 1 < cells.size) clean(cells[i + 1].text()) else ""
            }
            HostelInfo(value("Block Name"), value("Room No."), value("Bed Type"), value("Mess Information"))
                .takeIf { it.block.isNotEmpty() || value("Application Number").isNotEmpty() }
        }
        return Profile(name, after("GENDER"), photo, hostel)
    }

    /** Visible cell texts of a request row: cells holding only hidden inputs or buttons are skipped. */
    private fun requestValues(tr: Element): List<String> =
        tr.select("td").filter { it.selectFirst("input[type=hidden], input[type=button]") == null }.map { clean(it.text()) }

    /** `#LateHourStatusTable`: the last 8 visible cells are venue, event, details, applied to, from, to, time, status. */
    fun nightSlips(html: String): List<NightSlip> {
        val table = Jsoup.parse(html).getElementById("LateHourStatusTable") ?: return emptyList()
        return table.select("tbody tr").mapNotNull { tr ->
            val v = requestValues(tr)
            if (v.size < 8) return@mapNotNull null
            val (venue, event, details, appliedTo, from) = v.takeLast(8)
            val rest = v.takeLast(3)
            NightSlip(
                venue = venue,
                purpose = if (event.isNotEmpty() && details.isNotEmpty()) "$event - $details" else details.ifEmpty { event },
                appliedTo = appliedTo,
                from = from,
                to = rest[0],
                time = rest[1],
                status = rest[2],
            )
        }
    }

    /** `#LeaveAppliedTable` / `#LeaveHistoryTable`: the last 6 visible cells are place, reason, type, from, to, status. */
    fun leaves(html: String, tableId: String): List<Leave> {
        val table = Jsoup.parse(html).getElementById(tableId) ?: return emptyList()
        return table.select("tbody tr").mapNotNull { tr ->
            val v = requestValues(tr)
            if (v.size < 6) return@mapNotNull null
            val l = v.takeLast(6)
            Leave(l[0], l[1], l[2], l[3], l[4], l[5])
        }
    }

    /**
     * `POST /hrms/EmployeeSearch1ForStudent` + empId: label/value rows. Null when the page
     * has no name (unknown id, or VTOP changed).
     */
    fun faculty(html: String, empId: String): FacultyInfo? {
        val fields = Jsoup.parse(html).select("table tr").mapNotNull { tr ->
            val c = tr.select("td")
            if (c.size < 2) null else clean(c[0].text()).uppercase() to clean(c[1].text())
        }.toMap()
        val name = fields["NAME OF THE FACULTY"].orEmpty().ifEmpty { return null }
        return FacultyInfo(
            empId = empId,
            name = name,
            designation = fields["DESIGNATION"].orEmpty(),
            department = fields["NAME OF DEPARTMENT"].orEmpty(),
            school = fields["SCHOOL / CENTRE NAME"].orEmpty(),
            email = fields["E-MAIL ID"].orEmpty(),
            cabin = fields["CABIN NUMBER"].orEmpty(),
        )
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
        val curriculum = doc.select("table.customTable").firstOrNull { "Curriculum Details" in it.text() }
            ?.select("tr.tableContent, tr.fixedContent")?.mapNotNull { tr ->
                val c = tr.select("td")
                if (c.size < 3) return@mapNotNull null
                val name = clean(c[0].text()).replace(Regex("""\(.*?\)"""), "").trim()
                val required = number(c[1].text()) ?: return@mapNotNull null
                CreditDomain(name, required, number(c[2].text()) ?: 0.0)
            }.orEmpty()
        return GradeHistory(cgpa, earned, entries, curriculum)
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
