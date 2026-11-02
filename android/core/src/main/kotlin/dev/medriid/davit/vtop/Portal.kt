package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import java.util.Locale

/** One PDF on VTOP's circular portal. [date] is yyyy-MM-dd, or empty if VTOP gave none. */
@Serializable
data class Circular(val id: String, val title: String, val date: String, val office: String, val group: String)

/** A research project a faculty member has opened to students. [posted] is yyyy-MM-dd, or empty. */
@Serializable
data class FacultyProject(val id: String, val title: String, val area: String, val posted: String, val faculty: String, val school: String)

/** Something on VTOP that only opens for a while. [note] is VTOP's reason when it's shut. */
@Serializable
data class VtopWindow(val id: String, val title: String, val open: Boolean, val note: String)

/** One line of VTOP's activity history. */
@Serializable
data class SignIn(val activity: String, val time: String, val ip: String, val app: String)

@Serializable
data class Facility(val name: String, val fee: String, val seats: String)

@Serializable
data class FacilityStatus(val name: String, val status: String)

@Serializable
data class Sports(val facilities: List<Facility> = emptyList(), val mine: List<FacilityStatus> = emptyList())

@Serializable
data class ClubRow(val name: String, val type: String, val year: String, val status: String)

@Serializable
data class ClubDirectory(val mine: List<ClubRow> = emptyList(), val all: List<ClubRow> = emptyList())

@Serializable
data class FaqItem(val domain: String, val process: String, val question: String, val answer: String)

/** A line from VTOP's code of conduct: what's not allowed and what follows. */
@Serializable
data class MalpracticeRule(val exam: String, val nature: String, val action: String)

/** A table from one of VTOP's other exam pages. */
@Serializable
data class ExamTable(val title: String, val table: SimpleTable)

/** A certificate VTOP's own form would download: [path] takes [params]. */
@Serializable
data class Certificate(val source: String, val title: String, val detail: String, val path: String, val params: Map<String, String>) {
    val key: String get() = "cert:" + path + params.values.joinToString("|")
}

/** Read-only pages from the rest of VTOP's menu. Nothing here applies, registers or pays. */
object Portal {
    /** What [VtopClient.openWindows] watches: id, name and the menu page that says whether it's open. */
    val Windows = listOf(
        Triple("transport", "Transport registration", "/transport/transportRegistration"),
        Triple("internship", "Industrial internship", "/internship/InternshipRegistration"),
        Triple("mooc", "MOOC registration", "/academics/mooc/studentRegistration"),
        Triple("exc", "Extra-curricular courses", "/academics/exc/studentRegistration"),
        Triple("migration", "Programme migration", "/admissions/programmeMigration"),
        Triple("hostel", "Hostel room booking", "/hostels/online/room/allotment/open"),
        Triple("convocation", "Convocation", "/convocation/entryPage"),
        Triple("pat", "PAT registration", "/pat/PatRegistrationProcess"),
    )

    private val closedWords = listOf(
        "date is over", "date has been closed", "last date for applying", "no active registration",
        "not eligible", "not permitted", "not registered", "not enabled", "closed",
    )

    private val shortMonth = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH)
    private val numericDay = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

    private fun day(text: String, format: DateTimeFormatter): String =
        runCatching { LocalDate.parse(text, format).toString() }.getOrDefault("")

    /** `admissions/costCentreCircularsViewPageController`: a tree of office, group and "name.pdf [ 07-SEP-2026 ]" links. */
    fun circulars(html: String): List<Circular> = Jsoup.parse(html).select("a[onclick*=viewCertificate]").mapNotNull { a ->
        val id = Regex("""viewCertificate\('([^']+)'\)""").find(a.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
        val text = clean(a.text())
        val date = Regex("""\[\s*(\d{2}-[A-Za-z]{3}-\d{4})\s*]""").find(text)?.groupValues?.get(1).orEmpty()
        // Going up from the link: its own <li> has no label, then the group's, and the office's last.
        val path = a.parents().filter { it.tagName() == "li" }.mapNotNull { it.selectFirst("> span")?.text()?.let(::clean) }
        Circular(id, circularTitle(text), day(date, shortMonth), path.lastOrNull().orEmpty(), path.firstOrNull().orEmpty())
    }.distinctBy { it.id }

    /** "011_ACAD. CALEN. FOR WINTER_07.09.2026.pdf [ 07-SEP-2026 ]" becomes "ACAD. CALEN. FOR WINTER". */
    internal fun circularTitle(text: String): String =
        text.substringBefore(" [").trim()
            .replace(Regex("""\.pdf$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[_\s]+\d{2}\.\d{2}\.\d{4}$"""), "")
            .replace(Regex("""^\d+[_\s]+"""), "")
            .replace('_', ' ')
            .trim()

    /** `fpp/studentProposal`: `PROJECT Title | RESEARCH AREA | POSTED ON | FACULTY DETAILS | ACTION`. */
    fun projects(html: String): List<FacultyProject> {
        val el = Jsoup.parse(html).select("table").firstOrNull { "RESEARCH AREA" in it.text() } ?: return emptyList()
        val t = Html.table(el)
        val title = t.find("Title")
        val area = t.find("Area")
        val posted = t.find("Posted")
        val faculty = t.find("Faculty")
        return t.rows.indices.mapNotNull { i ->
            val id = t.elements[i].selectFirst("[data-id]")?.attr("data-id").orEmpty()
            val name = t.value(i, title).ifEmpty { return@mapNotNull null }
            val who = t.value(i, faculty)
            FacultyProject(
                id.ifEmpty { name }, name, t.value(i, area), day(t.value(i, posted), numericDay),
                who.substringBeforeLast(" - ").trim(), if (" - " in who) who.substringAfterLast(" - ").trim() else "",
            )
        }
    }

    /** Whether a registration page is open: shut if it says one of VTOP's closed lines, with that line as the note. */
    fun window(id: String, title: String, html: String): VtopWindow {
        val text = clean(Jsoup.parse(html).text())
        val low = text.lowercase()
        val at = closedWords.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull()
            ?: return VtopWindow(id, title, true, "")
        val start = text.lastIndexOfAny(charArrayOf('.', '!', ':'), at).let { if (it < 0) (at - 60).coerceAtLeast(0) else it + 1 }
        val end = text.indexOfAny(charArrayOf('.', '!'), at).let { if (it < 0) (at + 60).coerceAtMost(text.length) else it + 1 }
        return VtopWindow(id, title, false, text.substring(start, end).trim().take(100))
    }

    /** `show/login/history`: `# | Activity Type | Date & Time | Ip Address | App. Name`, newest first. */
    fun signIns(html: String): List<SignIn> {
        val el = Jsoup.parse(html).select("table").firstOrNull { "Ip Address" in it.text() } ?: return emptyList()
        val t = Html.table(el)
        return t.rows.indices.map { i ->
            SignIn(t.value(i, t.find("Activity")), t.value(i, t.find("Date")), t.value(i, t.find("Ip")), t.value(i, t.find("App")))
        }
    }

    /** `phyedu/facilityAvailable`: the table of facilities, and two-cell tables of what you've registered for. */
    fun sports(html: String): Sports {
        val tables = Jsoup.parse(html).select("table")
        val list = tables.firstOrNull { "Facility Name" in it.text() }
        val facilities = list?.let { el ->
            val t = Html.table(el)
            t.rows.indices.mapNotNull { i ->
                val name = t.value(i, t.find("Facility")).ifEmpty { return@mapNotNull null }
                Facility(name, t.value(i, t.find("Fees")), t.value(i, t.find("seats")))
            }
        }.orEmpty()
        val mine = tables.filter { it !== list }.flatMap { el ->
            el.select("tr").mapNotNull { tr ->
                val c = tr.select("> td").map { clean(it.text()) }
                if (c.size == 2 && c.all { it.isNotEmpty() }) FacilityStatus(c[0], c[1]) else null
            }
        }
        return Sports(facilities, mine)
    }

    /** `event/swf/student/loadClubChapterEnrollmentPage`: your clubs (has "Team Name"), then every club. */
    fun clubDirectory(html: String): ClubDirectory {
        val tables = Jsoup.parse(html).select("table").filter { "Association Name" in it.text() }
        val mineEl = tables.firstOrNull { "Team Name" in it.text() }
        val allEl = tables.firstOrNull { it !== mineEl }
        return ClubDirectory(mineEl?.let(::clubRows).orEmpty(), allEl?.let(::clubRows).orEmpty())
    }

    private fun clubRows(el: Element): List<ClubRow> {
        val t = Html.table(el)
        val name = t.find("Association")
        val year = t.find("Year")
        val status = t.find("Status")
        return t.rows.indices.mapNotNull { i ->
            val full = t.value(i, name).ifEmpty { return@mapNotNull null }
            val type = Regex("""\(([^()]+)\)\s*$""").find(full)?.groupValues?.get(1).orEmpty()
            // "Date Closed" sits in the unlabelled last column when the status cell is empty.
            val state = t.value(i, status).ifEmpty { t.rows[i].lastOrNull().orEmpty().takeIf { it != full }.orEmpty() }
            ClubRow(full.removeSuffix("($type)").trim(), type, t.value(i, year), state)
        }
    }

    /** `processPreviewFaqList`: a `.panel` per question, with its answer in `.panel-collapse`. */
    fun faqs(html: String, domain: String, process: String): List<FaqItem> = Jsoup.parse(html).select(".panel").mapNotNull { p ->
        val question = p.selectFirst(".panel-title")?.text()?.let(::clean).orEmpty()
        val answer = p.selectFirst(".panel-collapse")?.text()?.let(::clean).orEmpty()
        if (question.isEmpty()) null else FaqItem(domain, process, question, answer)
    }

    /** `examinations/malpracticePunishmentDetails`: the CAT/MTT table, then the FAT one. */
    fun malpractice(html: String): List<MalpracticeRule> =
        Jsoup.parse(html).select("table").filter { "Nature of Malpractice" in it.text() }.flatMapIndexed { n, el ->
            val t = Html.table(el)
            t.rows.indices.mapNotNull { i ->
                val nature = t.value(i, t.find("Nature")).ifEmpty { return@mapNotNull null }
                MalpracticeRule(if (n == 0) "CAT and MTT" else "FAT", nature, t.value(i, t.find("Action")))
            }
        }

    /** The regulations PDF, which VTOP embeds in its page as base64; null if it isn't there. */
    fun regulationsPdf(html: String): ByteArray? {
        val marker = "data:application/pdf;base64,"
        val from = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        var end = from
        while (end < html.length && (html[end].isLetterOrDigit() || html[end] == '+' || html[end] == '/' || html[end] == '=')) end++
        return runCatching { Base64.getDecoder().decode(html.substring(from, end)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The rows of a certificate page whose buttons call [function], against its `#downloadForm`.
     * [names] are that function's arguments in order; the ones in [send] go to the form.
     */
    private fun certificates(
        html: String,
        source: String,
        function: String,
        names: List<String>,
        send: Set<String>,
        title: (List<String>) -> Pair<String, String>,
    ): List<Certificate> {
        val doc = Jsoup.parse(html)
        val path = doc.selectFirst("#downloadForm")?.attr("action")?.trim('/').orEmpty()
        if (path.isEmpty()) return emptyList()
        return doc.select("tr").mapNotNull { tr ->
            val call = tr.selectFirst("[onclick*=$function]")?.attr("onclick") ?: return@mapNotNull null
            val args = Regex("""'([^']*)'""").findAll(call).map { it.groupValues[1] }.toList()
            if (args.size != names.size) return@mapNotNull null
            val (name, detail) = title(tr.select("> td").map { clean(it.text()) })
            Certificate(source, name, detail, "/$path", names.zip(args).filter { it.first in send }.toMap())
        }
    }

    fun gravitas(html: String) = certificates(html, "graVITas", "downloadCertificate", listOf("year", "eventId", "participantId"), setOf("eventId", "participantId")) { c ->
        c.getOrElse(2) { "Participant certificate" } to c.getOrElse(1) { "" }
    }

    fun universityDay(html: String) = certificates(html, "University Day", "doDownloadIndividualCertificate", listOf("awardId"), setOf("awardId")) { c ->
        c.getOrElse(2) { "Certificate" } to c.getOrElse(1) { "" }
    }

    fun sixSigma(html: String) = certificates(html, "Six Sigma", "downloadCertificate", listOf("monthYear", "registerNumber"), setOf("monthYear", "registerNumber")) { _ ->
        "Six Sigma" to ""
    }
}
