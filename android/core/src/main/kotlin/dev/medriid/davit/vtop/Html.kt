package dev.medriid.davit.vtop

import org.jsoup.nodes.Element

/** Small pieces every parser shares. */
internal object Html {
    private val space = Regex("[\\s\\u00a0]+")

    /** Collapses runs of whitespace (non-breaking spaces too) and trims. */
    fun clean(s: String) = s.replace(space, " ").trim()

    fun number(s: String): Double? = clean(s).replace(",", "").replace("₹", "").trim().toDoubleOrNull()

    fun cells(tr: Element): List<String> = tr.select("> th, > td").map { clean(it.text()) }

    /**
     * A table read by its header text rather than by position, so a column VTOP adds or
     * moves doesn't shift every value. [find] returns the first column whose header
     * contains any of the words, or -1.
     */
    class Table(val headers: List<String>, val rows: List<List<String>>, val elements: List<Element>) {
        fun find(vararg words: String): Int =
            headers.indexOfFirst { h -> words.any { h.contains(it, ignoreCase = true) } }

        fun value(row: Int, column: Int): String = if (column < 0) "" else rows[row].getOrElse(column) { "" }
    }

    /** The first row with `th` cells, or the first row, is the header; the rest are data. Empty rows are dropped. */
    fun table(t: Element): Table {
        val trs = t.select("tr").filter { it.closest("table") == t }
        if (trs.isEmpty()) return Table(emptyList(), emptyList(), emptyList())
        val head = trs.firstOrNull { it.selectFirst("> th") != null && it.selectFirst("> td") == null } ?: trs.first()
        val body = trs.filter { it != head && cells(it).any { c -> c.isNotEmpty() } }
        return Table(cells(head), body.map(::cells), body)
    }

    /** `label | value` rows (proctor details, library dues…), in order. Rows without a value are skipped. */
    fun labelValues(t: Element): List<Pair<String, String>> =
        t.select("tr").mapNotNull { tr ->
            val c = cells(tr).filter { it.isNotEmpty() }
            if (c.size < 2) null else c[0].trimEnd(':', ' ') to c[1]
        }

    /** A form's fields as a browser would send them (no files or buttons; unchecked boxes left out). */
    fun formFields(form: Element): List<Pair<String, String>> = form.select("input, select, textarea").mapNotNull(::fieldOf)

    /** How many of [form]'s fields come before [input] in the page: where a browser puts the file. */
    fun fieldsBefore(form: Element, input: Element): Int {
        val all = form.select("input, select, textarea")
        return all.take(all.indexOf(input).coerceAtLeast(0)).count { fieldOf(it) != null }
    }

    private fun fieldOf(e: Element): Pair<String, String>? {
        val name = e.attr("name")
        if (name.isEmpty() || e.hasAttr("disabled")) return null
        return when (e.tagName()) {
            "select" -> name to (e.selectFirst("option[selected]") ?: e.selectFirst("option"))?.attr("value").orEmpty()
            "textarea" -> name to e.text()
            else -> when (e.attr("type").lowercase()) {
                "file", "button", "submit", "reset", "image" -> null
                "checkbox", "radio" -> if (e.hasAttr("checked")) name to e.attr("value").ifEmpty { "on" } else null
                else -> name to e.attr("value")
            }
        }
    }
}
