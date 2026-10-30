package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** A club or chapter event on VTOP's Event Registration page. [state] is VTOP's word when you can't register ("Closed", "Limit Exceeded"). */
@Serializable
data class ClubListing(
    val id: String,
    val club: String,
    val title: String,
    val description: String,
    /** "11-Dec-2026 - 12-Dec-2026" as VTOP writes it. */
    val dates: String,
    val time: String,
    val venue: String,
    /** "2026-10-27 - 2026-12-11": when registration is open. */
    val registration: String,
    val canRegister: Boolean,
    val state: String,
)

/** An event you registered for, with its certificates when VTOP has them. */
@Serializable
data class RegisteredEvent(
    val id: String,
    val club: String,
    val title: String,
    val dates: String,
    val venue: String,
    val status: String,
    val participation: Boolean,
    val winner: Boolean,
)

/** A club board member's certificate (year, club, your position). */
@Serializable
data class BoardCertificate(val year: String, val club: String, val position: String, val associationId: String, val generatedYear: String, val roleId: String)

/** A certificate from VTOP's General Events page. */
@Serializable
data class GeneralCertificate(val name: String, val eventId: String, val certificateId: String, val participantId: String)

@Serializable
data class ClubEvents(
    val upcoming: List<ClubListing> = emptyList(),
    val registered: List<RegisteredEvent> = emptyList(),
    val board: List<BoardCertificate> = emptyList(),
)

/**
 * One class on VTOP's Course Feedback 24x7 page. [open] while it takes feedback (a box and
 * Submit); [given] is what the page shows instead once it's sent, when it shows anything.
 */
data class ClassFeedback(
    val classId: String,
    val code: String,
    val title: String,
    val type: String,
    val faculty: String,
    val slot: String,
    val venue: String,
    val open: Boolean,
    val given: String,
    /** VTOP's limit on the text (its counter stops at 100). */
    val maxLength: Int = 100,
)

/** VTOP's club and chapter pages: event registration and the certificate lists. */
object Clubs {

    /**
     * `event/swf/loadEventRegistration`: `#dataTable1` upcoming events (register button
     * `doEventRegistraiton('SWF…')` or a red word), `#dataTable2` your registrations with
     * certificate buttons, `#dataTable3` board certificates.
     */
    fun page(html: String): ClubEvents {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val upcoming = doc.getElementById("dataTable1")?.let { el ->
            val t = Html.table(el)
            val club = t.find("Association")
            val title = t.find("Title")
            val date = t.find("Event Date")
            val time = t.find("Time")
            val reg = t.find("Registration")
            val venue = t.find("Venue")
            val action = t.find("Register")
            t.elements.mapIndexedNotNull { i, tr ->
                val cells = tr.select("> td")
                val button = tr.selectFirst("[onclick*=doEventRegistraiton]")
                val id = button?.let { idIn(it.attr("onclick")) }
                    ?: tr.selectFirst("[onclick*=doShowEventdescription]")?.let { idIn(it.attr("onclick")) }
                    ?: return@mapIndexedNotNull null
                val description = tr.selectFirst(".modal-body")?.text()?.let(::clean).orEmpty()
                ClubListing(
                    id = id,
                    club = t.value(i, club),
                    title = cells.getOrNull(title)?.let { textOutsideModal(it) }.orEmpty(),
                    description = description,
                    dates = t.value(i, date),
                    time = t.value(i, time),
                    venue = t.value(i, venue),
                    registration = t.value(i, reg),
                    canRegister = button != null,
                    state = if (button != null) "" else cells.getOrNull(action)?.text()?.let(::clean).orEmpty(),
                )
            }
        }.orEmpty()
        val registered = doc.getElementById("dataTable2")?.let { el ->
            val t = Html.table(el)
            val club = t.find("Association")
            val title = t.find("Title")
            val date = t.find("Event Date")
            val venue = t.find("Venue")
            val status = t.find("Status")
            t.elements.mapIndexedNotNull { i, tr ->
                val cells = tr.select("> td")
                val part = tr.selectFirst("[name=downloadBtn1]")
                val win = tr.selectFirst("[name=downloadBtn2]")
                val id = part?.attr("data-eventid")?.takeIf { it.isNotBlank() } ?: win?.attr("data-eventid")?.takeIf { it.isNotBlank() }
                    ?: tr.selectFirst("[onclick*=doShowEventdescription]")?.let { idIn(it.attr("onclick")) }
                    ?: tr.selectFirst("[data-eventid]")?.attr("data-eventid")
                    ?: return@mapIndexedNotNull null
                RegisteredEvent(
                    id, t.value(i, club), cells.getOrNull(title)?.let { textOutsideModal(it) }.orEmpty(),
                    t.value(i, date), t.value(i, venue), t.value(i, status), part != null, win != null,
                )
            }
        }.orEmpty()
        val board = doc.getElementById("dataTable3")?.let { el ->
            val t = Html.table(el)
            val year = t.find("Year")
            val club = t.find("Club")
            val position = t.find("Position")
            t.elements.mapIndexedNotNull { i, tr ->
                val b = tr.selectFirst("[name=downloadBtn][data-associationid]") ?: return@mapIndexedNotNull null
                BoardCertificate(t.value(i, year), t.value(i, club), t.value(i, position), b.attr("data-associationid"), b.attr("data-generatedyear"), b.attr("data-roleid"))
            }
        }.orEmpty()
        return ClubEvents(upcoming, registered, board)
    }

    /** `events/swf/certificateDownload`: `Event Name | Download`, each `downloadCertificate(eventId, certificateId, participantId)`. */
    fun generalCertificates(html: String): List<GeneralCertificate> {
        val doc = Jsoup.parse(html)
        return doc.select("[onclick*=downloadCertificate(]").mapNotNull { b ->
            val args = Regex("""downloadCertificate\(([^)]*)\)""").find(b.attr("onclick"))?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('\'', '"', ' ') } ?: return@mapNotNull null
            if (args.size < 3) return@mapNotNull null
            val name = b.closest("tr")?.selectFirst("td")?.text()?.let(::clean).orEmpty()
            GeneralCertificate(name, args[0], args[1], args[2])
        }
    }

    /**
     * `academics/common/processViewFeedbackList`: a box per class, headed "CODE - Title" and
     * "( Type )", with `Class Number | Faculty | Slot | Venue` and, while open, a
     * `textarea#courseFeedback_<classId>` and a Submit whose value is the class id.
     */
    fun classFeedback(html: String): List<ClassFeedback> {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        return doc.select("h5").mapNotNull { h ->
            val box = h.parents().firstOrNull { p -> p.select("table").any { "Class Number" in it.text() } } ?: return@mapNotNull null
            val spans = h.select("span span").map { clean(it.text()) }.filter { it.isNotEmpty() }.ifEmpty { listOf(clean(h.text())) }
            val (code, title) = spans[0].split(" - ", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            val type = spans.getOrNull(1)?.trim('(', ')', ' ').orEmpty()
            val t = Html.table(box.select("table").first { "Class Number" in it.text() })
            val area = box.selectFirst("textarea[id^=courseFeedback_]")
            val button = box.selectFirst("button[onclick*=processSaveCourseFeedback]")
            val classId = button?.attr("value")?.takeIf { it.isNotBlank() } ?: area?.id()?.removePrefix("courseFeedback_")
                ?: t.value(0, t.find("Class Number")).takeIf { t.rows.isNotEmpty() } ?: return@mapNotNull null
            val faculty = if (t.rows.isEmpty()) "" else t.value(0, t.find("Faculty"))
            val given = if (area == null) {
                box.select("table").firstOrNull { "Description" in it.text() }?.let { Html.table(it) }?.let { d -> if (d.rows.isEmpty()) "" else d.rows[0].firstOrNull().orEmpty() }.orEmpty()
            } else ""
            ClassFeedback(
                classId, code, title, type,
                faculty = faculty.takeUnless { it.equals("NIL", true) }.orEmpty(),
                slot = (if (t.rows.isEmpty()) "" else t.value(0, t.find("Slot"))).takeUnless { it.equals("NIL", true) }.orEmpty(),
                venue = (if (t.rows.isEmpty()) "" else t.value(0, t.find("Venue"))).takeUnless { it.equals("NIL", true) }.orEmpty(),
                open = area != null && button != null,
                given = given,
            )
        }.distinctBy { it.classId }
    }

    private fun idIn(onclick: String): String? = Regex("""\(\s*['"]([^'"]+)['"]""").find(onclick)?.groupValues?.get(1)?.trim()

    /** A title cell also holds the description's modal; only its own text. */
    private fun textOutsideModal(td: Element): String {
        val copy = td.clone()
        copy.select(".modal, button").remove()
        return clean(copy.text())
    }
}
