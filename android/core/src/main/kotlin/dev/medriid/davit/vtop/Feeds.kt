package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * The home page's feeds (`POST get/...`) and the pages behind the sidebar that only show
 * things: proctor, HOD and dean, messages, gate history, receipts, curriculum and so on.
 * Every parser here reads tables by their header text, so a moved column doesn't break it.
 */
object Feeds {

    // ---- dashboard -------------------------------------------------------------------

    /** `get/dashboard/current/semester/course/details`: `# | CODE- Name | ETH | 84.0 | remark`. */
    fun remarks(html: String): List<CourseRemark> {
        val table = Jsoup.parse(html).select("table").firstOrNull { "Remark" in it.text() } ?: return emptyList()
        return table.select("tbody tr").mapNotNull { tr ->
            val tds = tr.select("> td")
            if (tds.size < 4) return@mapNotNull null
            val spans = tds[0].select("span").map { clean(it.text()) }.filter { it.isNotEmpty() }
            val (code, name) = if (spans.size >= 2) spans[0].trimEnd('-', ' ') to spans[1]
            else clean(tds[0].text()).split("-", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            if (code.isEmpty()) return@mapNotNull null
            CourseRemark(code, name, clean(tds[1].text()), Html.number(tds[2].text()), clean(tds[3].text()))
        }
    }

    /** `get/last/five/feedbacks`: `# | title | category | Completed / Not Completed`. */
    fun feedbacks(html: String): List<FeedbackItem> {
        val table = Jsoup.parse(html).selectFirst("table") ?: return emptyList()
        val t = Html.table(table)
        val title = t.find("Feedback").takeIf { it >= 0 } ?: 1
        val category = t.find("Category").takeIf { it >= 0 } ?: 2
        val status = t.find("Status").takeIf { it >= 0 } ?: 3
        return t.rows.indices.mapNotNull { i ->
            val name = t.value(i, title).ifEmpty { return@mapNotNull null }
            val s = t.value(i, status)
            FeedbackItem(name, t.value(i, category), s.equals("Completed", ignoreCase = true))
        }
    }

    private val monthYear = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM-yyyy").toFormatter(Locale.ENGLISH)

    /**
     * `get/scheduled/events`: a card per day (`col-2` with the day and "Oct-2026") holding a
     * `.card-body.small` per event: title, (category), date as yyyy-MM-dd, the club.
     */
    fun events(html: String): List<ClubEvent> {
        val doc = Jsoup.parse(html)
        return doc.select(".card").flatMap { card ->
            val day = card.selectFirst(".h5")?.text()?.let(::clean)?.toIntOrNull()
            val month = card.selectFirst(".subtitle")?.text()?.let { runCatching { YearMonth.parse(clean(it), monthYear) }.getOrNull() }
            val cardDate = if (day != null && month != null && day in 1..month.lengthOfMonth()) month.atDay(day) else null
            card.select(".card-body.small").mapNotNull { b ->
                val title = b.selectFirst(".card-title")?.text()?.let(::clean).orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val category = b.selectFirst(".fst-italic")?.text()?.let(::clean).orEmpty()
                val date = Regex("""\d{4}-\d{2}-\d{2}""").find(b.text())?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: cardDate
                ClubEvent(date, title, category, b.selectFirst("small")?.text()?.let(::clean).orEmpty())
            }
        }.distinctBy { listOf(it.date, it.title, it.club) }
    }

    /**
     * Spot-light, in `/vtop/content`: sections (`#spotlightAcad`, `#spotlightEvent`…) with an
     * `h5` title and a list of links. A link is either a web page or `vtopDownload('path')`.
     */
    fun spotlight(html: String): List<SpotlightItem> {
        val doc = Jsoup.parse(html)
        val sections = doc.select(".spotlightMsgDivs").ifEmpty { doc.select("div[id^=spotlight]:has(> ul)") }
        return sections.flatMap { s ->
            val section = s.selectFirst("h5")?.text()?.let(::clean).orEmpty()
            s.select("li a").mapNotNull { a ->
                val title = clean(a.text()).ifEmpty { return@mapNotNull null }
                val download = Regex("""vtopDownload\('([^']+)'""").find(a.attr("onclick"))?.groupValues?.get(1)
                val href = a.attr("href").takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (download == null && href == null) return@mapNotNull null
                SpotlightItem(section, title, href, download)
            }
        }
    }

    // ---- people ----------------------------------------------------------------------

    /** `proctor/viewProctorDetails`: one label/value table. Null without a name. */
    fun proctor(html: String): Person? {
        val table = Jsoup.parse(html).selectFirst("table") ?: return null
        return Person("Proctor", Html.labelValues(table)).takeIf { it.name.isNotEmpty() }
    }

    /** `hrms/viewHodDeanDetails`: each label/value table under its heading ("DEAN", "Head of the Department (HoD)"). */
    fun hodDean(html: String): List<Person> {
        val doc = Jsoup.parse(html)
        return doc.select("table").mapNotNull { table ->
            val fields = Html.labelValues(table)
            val person = Person(headingBefore(table, doc).ifEmpty { "Contact" }, fields)
            if (person.name.isEmpty()) return@mapNotNull null
            val role = when {
                person.role.contains("dean", true) -> "Dean"
                person.role.contains("hod", true) || person.role.contains("head", true) -> "HOD"
                person.email.startsWith("dean", true) -> "Dean"
                person.email.startsWith("hod", true) -> "HOD"
                else -> person.role
            }
            person.copy(role = role)
        }
    }

    /** The nearest short text before [el] in document order that isn't inside a table. */
    private fun headingBefore(el: org.jsoup.nodes.Element, doc: Document): String {
        val all = doc.getAllElements()
        val at = all.indexOf(el)
        for (i in at - 1 downTo 0) {
            val e = all[i]
            if (e.closest("table") != null) continue
            val own = clean(e.ownText())
            if (own.isNotEmpty() && own.length < 60) return own
        }
        return ""
    }

    /**
     * Class or proctor messages. "No Messages…" means none. Tables are read by header
     * (faculty, course, message, date); the accordion layout (`.expand` + the next block) too.
     */
    fun messages(html: String): List<VtopMessage> {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val tables = doc.select("table").map(Html::table).filter { it.rows.isNotEmpty() }
        if (tables.isNotEmpty()) {
            return tables.flatMap { t ->
                val from = t.find("Faculty", "Sender", "From", "Name")
                val course = t.find("Course", "Class")
                val text = t.find("Message", "Content", "Subject", "Description")
                val date = t.find("Date", "Expir", "Sent", "Time")
                t.rows.indices.mapNotNull { i ->
                    val body = t.value(i, text).ifEmpty { t.rows[i].maxByOrNull { it.length }.orEmpty() }
                    if (body.isEmpty()) null else VtopMessage(t.value(i, from), t.value(i, course), body, t.value(i, date))
                }
            }
        }
        return doc.select(".expand").mapNotNull { e ->
            val head = clean(e.text()).removePrefix("+").removePrefix("-").trim()
            val body = e.nextElementSibling()?.text()?.let(::clean).orEmpty()
            if (head.isEmpty() && body.isEmpty()) null
            else VtopMessage(head.substringBefore(" - ").trim(), head.substringAfter(" - ", "").trim(), body.ifEmpty { head }, "")
        }
    }

    // ---- records ---------------------------------------------------------------------

    /** `getStudViewBioList`: whatever columns the gate log has. Empty when "No Record(S) Found". */
    fun table(html: String): SimpleTable {
        val table = Jsoup.parse(html).select("table").map(Html::table).firstOrNull { it.rows.isNotEmpty() }
            ?: return SimpleTable(emptyList(), emptyList())
        return SimpleTable(table.headers, table.rows)
    }

    /** `finance/getStudentReceipts`: `Invoice | Receipt | Date | Amount | Campus | View`. */
    fun receipts(html: String): List<Receipt> {
        val doc = Jsoup.parse(html)
        val el = doc.getElementById("dataTable") ?: doc.select("table").firstOrNull { "Receipt" in it.text() } ?: return emptyList()
        val t = Html.table(el)
        val inv = t.find("Invoice")
        val no = t.find("Receipt")
        val date = t.find("Date")
        val amount = t.find("Amount")
        val campus = t.find("Campus")
        return t.rows.indices.mapNotNull { i ->
            val n = t.value(i, no)
            if (n.isEmpty() && t.value(i, inv).isEmpty()) null
            else Receipt(t.value(i, inv), n, t.value(i, date), Html.number(t.value(i, amount)), t.value(i, campus))
        }
    }

    /** `finance/getStudentFeesIntimation`: a row per letter, its button carrying `data-batchid`. */
    fun feeLetters(html: String): List<FeeLetter> {
        val doc = Jsoup.parse(html)
        val el = doc.getElementById("feesIntimationLetterDataTable") ?: doc.select("table").firstOrNull { "Fee" in it.text() } ?: return emptyList()
        val t = Html.table(el)
        val year = t.find("Year")
        val term = t.find("Term")
        val what = t.find("Description")
        return t.elements.mapIndexedNotNull { i, tr ->
            val batch = tr.selectFirst("[data-batchid]")?.attr("data-batchid").orEmpty()
            if (batch.isEmpty()) null else FeeLetter(t.value(i, year), t.value(i, term), t.value(i, what), batch)
        }
    }

    /** `finance/libraryPayments`: the amounts only (`Koha Due Amount | ₹ 0.00`). Paying is never done here. */
    fun libraryDues(html: String): List<Pair<String, Double?>> {
        val table = Jsoup.parse(html).select("table").firstOrNull { "Due" in it.text() } ?: return emptyList()
        return Html.labelValues(table).map { (k, v) -> k to Html.number(v) }
    }

    private val regDate = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH)

    /**
     * `academics/common/studentAllocationView`: a table per activity, e.g. `REGISTRATION`,
     * then `Semester | Winter Semester 2026-27`, `Start | 01-Nov-2026 | 17:00:00`, `End | …`.
     */
    fun registration(html: String): List<RegistrationWindow> =
        Jsoup.parse(html).select("table").mapNotNull { table ->
            val rows = table.select("tr").map(Html::cells)
            fun row(label: String) = rows.firstOrNull { it.firstOrNull()?.equals(label, ignoreCase = true) == true }
            fun at(label: String): LocalDateTime? {
                val r = row(label) ?: return null
                val day = r.getOrNull(1)?.let { runCatching { LocalDate.parse(it, regDate) }.getOrNull() } ?: return null
                val time = r.getOrNull(2)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.MIDNIGHT
                return day.atTime(time)
            }
            val start = at("Start")
            val end = at("End")
            if (start == null && end == null) return@mapNotNull null
            val title = rows.firstOrNull { it.size == 1 && it[0].isNotEmpty() }?.first().orEmpty()
            RegistrationWindow(
                title.lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "Registration" },
                row("Semester")?.getOrNull(1).orEmpty(), start, end,
            )
        }

    /** `academics/common/Curriculum`: a row per category with `categoryOnClick('UCC')`, its credits and maximum. */
    fun curriculumCategories(html: String): List<CurriculumCategory> =
        Jsoup.parse(html).select("[onclick*=categoryOnClick]").mapNotNull { e ->
            val id = Regex("""categoryOnClick\('([^']+)'\)""").find(e.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val box = e.selectFirst(".symbol-label")
            val numbers = box?.select("span")?.map { Html.number(it.text()) }.orEmpty()
            val name = e.select(".col-6 span, span.d-block").firstOrNull()?.text()?.let(::clean)
                ?: clean(e.text()).substringAfterLast(' ')
            CurriculumCategory(id, name, numbers.getOrNull(0), numbers.getOrNull(1))
        }

    /**
     * `academics/common/curriculumCategoryView`: tabs (`button[data-bs-target=#cat-pane-N]`
     * named by their `span`) over panes of `S.No. | Code/Syllabus | Title | Type | Credit | L | T | P | J`.
     */
    fun curriculumCourses(html: String): List<CurriculumCourse> {
        val doc = Jsoup.parse(html)
        val names = doc.select("[data-bs-target]").associate { it.attr("data-bs-target").removePrefix("#") to clean(it.text()) }
        val panes = doc.select("[id^=cat-pane-]").ifEmpty { listOf(doc.body()) }
        return panes.flatMap { pane ->
            val group = names[pane.id()].orEmpty()
            pane.select("table").flatMap { el ->
                val t = Html.table(el)
                val code = t.find("Code")
                val title = t.find("Title")
                val type = t.find("Type")
                val credit = t.find("Credit")
                val l = t.headers.indexOf("L")
                t.elements.mapIndexedNotNull { i, tr ->
                    val c = tr.selectFirst("[data-coursecode]")?.attr("data-coursecode")?.takeIf { it.isNotBlank() }
                        ?: t.value(i, code).substringBefore(' ')
                    if (c.isEmpty()) return@mapIndexedNotNull null
                    val ltpj = if (l >= 0) t.rows[i].drop(l).take(4).joinToString("-") else ""
                    CurriculumCourse(group, c, t.value(i, title), t.value(i, type), Html.number(t.value(i, credit)), ltpj)
                }
            }
        }
    }

    /**
     * `examinations/examGradeView/doStudentGradeView`: the course table (headers with
     * "Course" and "Grade") and the GPA wherever the page prints "GPA". Empty for a
     * semester without results ("No Records Found").
     */
    fun semesterGrades(html: String, semesterId: String): SemesterGrades {
        val doc = Jsoup.parse(html)
        doc.select("script, style, select").remove()
        val table = doc.select("table").map(Html::table).firstOrNull { t -> t.find("Grade") >= 0 && t.find("Course", "Title") >= 0 }
        val courses = table?.let { t ->
            val code = t.find("Course Code", "Code")
            val title = t.find("Course Title", "Title")
            val credits = t.find("Credit")
            val grade = t.headers.indexOfLast { it.contains("Grade", true) && !it.contains("Type", true) }
            t.rows.indices.mapNotNull { i ->
                val c = t.value(i, code).ifEmpty { return@mapNotNull null }
                if (c.equals("Total", true)) return@mapNotNull null
                GradeEntry(c, t.value(i, title), Html.number(t.value(i, credits)) ?: 0.0, t.value(i, grade).takeUnless { it.isEmpty() || it == "-" }, "")
            }
        }.orEmpty()
        val gpa = Regex("""GPA\s*[:\-]?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(clean(doc.text()))?.groupValues?.get(1)?.toDoubleOrNull()
        return SemesterGrades(semesterId, gpa, courses)
    }

    // ---- hostel leave ------------------------------------------------------------------

    /** `hostels/student/leave/2`: `#LeaveRequestForm` with its `leaveCode` choices; "PROCTOR NAME: X (id)" above it. */
    fun leaveForm(html: String): LeaveForm? {
        val doc = Jsoup.parse(html)
        val form = doc.getElementById("LeaveRequestForm") ?: doc.selectFirst("form:has(select[name=leaveCode])") ?: return null
        val types = form.select("select[name=leaveCode] option").mapNotNull { o ->
            val v = o.attr("value").trim()
            if (v.isEmpty()) null else v to clean(o.text())
        }
        val text = clean(doc.text())
        val proctor = Regex("""PROCTOR NAME\s*:\s*(.+?\(\s*\w+\s*\))""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val hidden = form.select("input[type=hidden]").mapNotNull { e -> e.attr("name").takeIf { it.isNotEmpty() }?.let { it to e.attr("value") } }
        return LeaveForm(proctor, types, hidden)
    }

    /** What VTOP said after an application: the text of its message box, read as accepted or not. */
    fun leaveOutcome(html: String): LeaveOutcome {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val text = clean(doc.text()).ifEmpty { "VTOP sent back an empty page." }
        val lower = text.lowercase()
        val bad = listOf("already", "invalid", "error", "not ", "cannot", "can't", "fail", "please", "kindly", "required")
        val good = listOf("success", "applied", "submitted", "saved")
        return LeaveOutcome(good.any { it in lower } && bad.none { it in lower }, text.take(300))
    }

    /** The masked address in "OTP sent to your email ID ab***@vitstudent.ac.in". */
    internal fun maskedEmail(text: String): String =
        Regex("""[A-Za-z0-9._%+*-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").find(text)?.value.orEmpty()

    /** Red text, alerts and `.text-danger`: how VTOP pages say no. */
    internal fun warnings(doc: Document): String? =
        doc.select("span[style*=red], font[color=red], .text-danger, .alert-danger, .alert")
            .map { clean(it.text()) }.filter { it.isNotEmpty() && it != "-" }.distinct().joinToString(" ").ifEmpty { null }
}
