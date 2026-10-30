package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One field of a VTOP form as the page renders it, for the app to draw its own way. */
data class FormField(
    val name: String,
    val label: String,
    val kind: Kind,
    /** What the page filled in (or the selected option). */
    val value: String = "",
    /** Choices as value → label, for [Kind.Choice]. */
    val options: List<Pair<String, String>> = emptyList(),
    val maxLength: Int? = null,
    val required: Boolean = false,
    val readOnly: Boolean = false,
) {
    enum class Kind {
        Text, LongText, Number, Choice,
        /** `type=date`: sent as yyyy-MM-dd. */
        Date,
        /** A text box VTOP fills with a date picker: sent as dd-MMM-yyyy ("20-Dec-2026"). */
        VtopDate,
        /** `type=time`: sent as HH:mm. */
        Time,
    }
}

/**
 * A VTOP form read at runtime: what to show ([fields]), what goes along unseen ([hidden]),
 * and, for the `requestUpdate` kind of page, the button's `control` name and `urltext`.
 */
data class VtopForm(
    val id: String,
    val fields: List<FormField>,
    val hidden: List<Pair<String, String>>,
    val control: String = "",
    val submitTo: String = "",
)

/** A page that holds a form, or (when VTOP won't give one) what it says instead: "Already a request is pending". */
data class FormPage(val form: VtopForm?, val message: String)

/** What VTOP said after a request was sent. [ok] when it reads as done. */
data class Reply(val ok: Boolean, val message: String)

/** Reading VTOP's forms and its answers to them, whatever the page. */
object Forms {

    /**
     * The form [id] (or the first form with a visible field) on [html]. Labels come from
     * `label[for]`, the label in the same group, the cell before in a `label | field`
     * table, or the placeholder.
     */
    fun read(html: String, id: String? = null): FormPage {
        val doc = Jsoup.parse(html)
        doc.select("script, style").remove()
        val form = (id?.let { doc.getElementById(it) } ?: doc.select("form").firstOrNull { visible(it).isNotEmpty() })
            ?.takeIf { visible(it).isNotEmpty() }
            ?: return FormPage(null, message(doc))
        val fields = mutableListOf<FormField>()
        val radios = mutableSetOf<String>()
        for (e in visible(form)) {
            val name = e.attr("name")
            val label = labelOf(e, doc)
            val required = e.hasAttr("required") || label.endsWith("*")
            val readOnly = e.hasAttr("readonly") || e.hasAttr("disabled")
            val max = e.attr("maxlength").toIntOrNull()
            val cleanLabel = label.trimEnd('*', ' ', ':')
            when {
                e.tagName() == "select" -> {
                    val options = e.select("option").mapNotNull { o ->
                        val v = o.attr("value").trim()
                        if (v.isEmpty()) null else v to clean(o.text())
                    }
                    val chosen = e.selectFirst("option[selected]")?.attr("value").orEmpty()
                    fields += FormField(name, cleanLabel, FormField.Kind.Choice, chosen, options, null, required, readOnly)
                }
                e.tagName() == "textarea" -> fields += FormField(name, cleanLabel, FormField.Kind.LongText, e.text(), maxLength = max, required = required, readOnly = readOnly)
                e.attr("type").equals("radio", true) -> {
                    if (!radios.add(name)) continue
                    val group = form.select("input[type=radio][name=$name]")
                    val options = group.map { r -> r.attr("value") to (labelFor(r, doc) ?: radioText(r)).ifEmpty { r.attr("value") } }
                    val chosen = group.firstOrNull { it.hasAttr("checked") }?.attr("value").orEmpty()
                    fields += FormField(name, groupLabel(group.first()!!).ifEmpty { cleanLabel }, FormField.Kind.Choice, chosen, options, null, true, readOnly)
                }
                else -> {
                    val type = e.attr("type").lowercase()
                    val kind = when {
                        type == "date" -> FormField.Kind.Date
                        type == "time" -> FormField.Kind.Time
                        type == "number" -> FormField.Kind.Number
                        name.contains("date", true) || e.hasClass("datepicker") -> FormField.Kind.VtopDate
                        name.contains("time", true) && type == "text" -> FormField.Kind.Time
                        else -> FormField.Kind.Text
                    }
                    fields += FormField(name, cleanLabel, kind, e.attr("value"), maxLength = max, required = required, readOnly = readOnly)
                }
            }
        }
        val hidden = form.select("input[type=hidden]").mapNotNull { h -> h.attr("name").takeIf { it.isNotEmpty() }?.let { it to h.attr("value") } }
        val button = form.select("[data-urltext]").firstOrNull { it.attr("data-isprimarycontrol") == "true" || it.attr("data-formname") == form.id() }
            ?: doc.select("[data-urltext][data-formname=${form.id().ifEmpty { "-" }}]").firstOrNull()
        return FormPage(
            VtopForm(form.id(), fields, hidden, button?.attr("data-controlname").orEmpty(), button?.attr("data-urltext").orEmpty()),
            message(doc),
        )
    }

    /** VTOP's answer to a request: its message box, `#info`/`#jsonBom` values, or the page's text. */
    fun reply(html: String): Reply {
        val doc = Jsoup.parse(html)
        // Pages that answer with a sweetalert keep the words in a script: `var info = "Saved";`.
        val scripted = doc.select("script").flatMap { s ->
            Regex("""var\s+(?:info|infoMessage)\s*=\s*["']([^"']+)["']""").findAll(s.data()).map { it.groupValues[1] }
        }
        val stated = (listOf("info", "jsonBom", "success", "message")
            .mapNotNull { doc.getElementById(it)?.let { e -> if (e.tagName() == "input") e.attr("value") else e.text() } } + scripted)
            .map(::clean).filter { it.isNotEmpty() && it != "null" }
        doc.select("script, style").remove()
        val text = stated.joinToString(" ").ifEmpty { message(doc) }.ifEmpty { clean(doc.text()) }.ifEmpty { "VTOP sent back an empty page." }
        val lower = text.lowercase()
        val bad = listOf("already", "invalid", "error", "not ", "cannot", "can't", "fail", "please", "kindly", "required", "exceed", "closed")
        val good = listOf("success", "applied", "submitted", "saved", "registered")
        return Reply(good.any { it in lower } && bad.none { it in lower }, text.take(300))
    }

    /** The page's own words: VTOP's `…Message` paragraphs, alerts and red text, or a short body. */
    internal fun message(doc: Document): String {
        val own = doc.select("[id$=Message], .alert, .text-danger, span[style*=red], font[color=red], h3, h4")
            .map { clean(it.text()) }.filter { it.isNotEmpty() && it != "-" }.distinct()
        if (own.isNotEmpty()) return own.joinToString(" ").take(300)
        return clean(doc.text()).take(200)
    }

    private fun visible(form: Element): List<Element> =
        form.select("input, select, textarea").filter { e ->
            val type = e.attr("type").lowercase()
            e.attr("name").isNotEmpty() && type !in setOf("hidden", "button", "submit", "reset", "image", "file", "checkbox")
        }

    private fun labelFor(e: Element, doc: Document): String? =
        e.id().takeIf { it.isNotEmpty() }?.let { id -> doc.select("label").firstOrNull { it.attr("for") == id } }?.text()?.let(::clean)?.takeIf { it.isNotEmpty() }

    private fun radioText(r: Element): String =
        clean(r.parent()?.ownText().orEmpty()).ifEmpty { clean(r.nextElementSibling()?.text().orEmpty()) }

    /** The label of a group of radios: the nearest label before them that isn't one of theirs. */
    private fun groupLabel(first: Element): String {
        var at: Element? = first.parent()
        repeat(3) {
            val label = at?.parent()?.children()?.takeWhile { it != at }?.lastOrNull { it.tagName() == "label" || it.selectFirst("label") != null }
            if (label != null) return clean(label.text())
            at = at?.parent()
        }
        return ""
    }

    private fun labelOf(e: Element, doc: Document): String {
        labelFor(e, doc)?.let { return it }
        // The label in the same group: <div><label>From *</label><div><input></div></div>.
        var group: Element? = e.parent()
        repeat(3) {
            val g = group ?: return@repeat
            if (g.tagName() == "td" || g.tagName() == "form") return@repeat
            // A group holding other fields too would lend this one their label.
            if (g.select("input:not([type=hidden]), select, textarea").size > 1) return@repeat
            g.selectFirst("label")?.text()?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { return it }
            group = g.parent()
        }
        // label | field in a table row: the cell before.
        e.closest("td")?.previousElementSibling()?.text()?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { return it }
        return e.attr("placeholder").ifEmpty { e.attr("name") }
    }
}

/** A course you can ask to see the paper of (or have re-evaluated): its checkbox value and the row as VTOP shows it. */
data class PaperCourse(val value: String, val code: String, val title: String, val details: List<Pair<String, String>>)

/** The paper seeing page after its choices: the courses offered, or VTOP's message ("No Records Found/Grades have been published"). */
data class PaperSeeingPage(val message: String, val courses: List<PaperCourse>, val fields: List<Pair<String, String>>)

object PaperSeeing {
    /**
     * `examinations/paperSeeing/PaperSeeingOnChange`: the message in `#jsonBom`, and a row per
     * course with a `frontValue` checkbox (rows not seen live; read by their headers).
     */
    fun page(html: String): PaperSeeingPage {
        val doc = Jsoup.parse(html)
        val message = clean(doc.getElementById("jsonBom")?.attr("value").orEmpty())
        val form = doc.getElementById("paperSeeingView")
        val fields = form?.let { Html.formFields(it) }?.filter { it.first != "frontValue" }.orEmpty()
        val courses = doc.select("input[name=frontValue]").mapNotNull { box ->
            val value = box.attr("value").ifBlank { return@mapNotNull null }
            val tr = box.closest("tr") ?: return@mapNotNull null
            val table = tr.closest("table")?.let(Html::table)
            val cells = Html.cells(tr)
            val headers = table?.headers.orEmpty()
            val details = cells.indices.mapNotNull { i ->
                val h = headers.getOrNull(i).orEmpty()
                val v = cells[i]
                if (v.isEmpty() || h.isEmpty() || h.contains("select", true) || h == "#" || h.contains("S.No", true) || h.contains("Sl", true) && v.all(Char::isDigit)) null else h to v
            }
            fun col(vararg words: String) = details.firstOrNull { (h, _) -> words.any { h.contains(it, true) } }?.second.orEmpty()
            PaperCourse(value, col("Course Code", "Code"), col("Course Title", "Title", "Course Name"), details)
        }
        return PaperSeeingPage(message, courses, fields)
    }
}
