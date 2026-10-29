package dev.medriid.davit.search

/**
 * Forgiving search over short records (course materials): every word of the query has to
 * land somewhere, as a whole word, the start of one, inside one, a typo of one (one edit
 * for 4 to 7 letters, two from 8), or the initials of a field ("dsa" for "Data Structures
 * and Algorithms"). Matches keep their character ranges so the UI can highlight them.
 *
 * Build the [Index] once per data set; [Index.search] is then a plain loop with no
 * allocation per document beyond the hits, fast enough for thousands of rows per keystroke.
 */
object Fuzzy {

    /** One searchable field, with how much a match in it counts (title over uploader). */
    data class Field(val name: String, val weight: Float)

    data class Hit<T>(val item: T, val score: Float, val ranges: Map<String, List<IntRange>>)

    /** A word of a field: lowercased text and where it sits in the original string. */
    private class Word(val text: String, val start: Int)

    private class Doc<T>(val item: T, val fields: List<List<Word>>, val initials: List<String>, val raw: List<String>)

    class Index<T>(items: List<T>, private val fields: List<Field>, read: (T) -> List<String>) {
        private val docs = items.map { item ->
            val values = read(item)
            val words = fields.indices.map { words(values.getOrElse(it) { "" }) }
            Doc(item, words, words.map { ws -> ws.filter { it.text.first().isLetter() && it.text !in Stop }.joinToString("") { it.text.take(1) } }, values)
        }

        val size: Int get() = docs.size

        /**
         * Hits for [query], best first. [filter] runs before any scoring. A blank query
         * returns everything that passes the filter, in the original order.
         */
        fun search(query: String, limit: Int = 300, filter: (T) -> Boolean = { true }): List<Hit<T>> {
            val terms = words(query).map { it.text }.filter { it.isNotEmpty() }
            if (terms.isEmpty()) return docs.asSequence().filter { filter(it.item) }.take(limit).map { Hit(it.item, 0f, emptyMap()) }.toList()
            val phrase = normal(query)
            val out = ArrayList<Hit<T>>()
            for (doc in docs) {
                if (!filter(doc.item)) continue
                var total = 0f
                var ranges: MutableMap<String, MutableList<IntRange>>? = null
                var allMatched = true
                for (term in terms) {
                    var best = 0f
                    var bestField = -1
                    var bestRange: IntRange? = null
                    for ((f, ws) in doc.fields.withIndex()) {
                        val weight = fields[f].weight
                        for (w in ws) {
                            val s = wordScore(term, w.text)
                            if (s * weight > best) {
                                best = s * weight
                                bestField = f
                                // A prefix lights up what was typed; anything else, the whole word.
                                val inside = if (w.text.startsWith(term)) 0 else w.text.indexOf(term).takeIf { term.length >= 3 } ?: -1
                                bestRange = if (inside >= 0) (w.start + inside) until (w.start + inside + term.length) else w.start until w.start + w.text.length
                            }
                        }
                        if (term.length >= 2 && doc.initials[f].startsWith(term) && INITIALS * weight > best) {
                            best = INITIALS * weight
                            bestField = f
                            bestRange = null
                        }
                    }
                    if (best <= 0f) {
                        allMatched = false
                        break
                    }
                    total += best
                    if (bestRange != null) {
                        if (ranges == null) ranges = HashMap()
                        ranges.getOrPut(fields[bestField].name) { ArrayList() }.add(bestRange)
                    }
                }
                if (!allMatched) continue
                // The query as typed, inside one field: a strong sign it's the one.
                for ((f, raw) in doc.raw.withIndex()) {
                    if (terms.size > 1 && normal(raw).contains(phrase)) total += PHRASE * fields[f].weight
                }
                out += Hit(doc.item, total, ranges.orEmpty())
            }
            out.sortByDescending { it.score }
            return if (out.size > limit) out.subList(0, limit) else out
        }
    }

    private const val EXACT = 10f
    private const val PREFIX = 8f
    private const val INSIDE = 5f
    private const val TYPO = 4f
    private const val INITIALS = 6f
    private const val PHRASE = 6f

    /** Words too common to carry an initial. */
    private val Stop = setOf("and", "of", "the", "for", "in", "to", "with", "using", "a", "an", "on")

    /** How well one query word matches one field word; 0 for no match. */
    internal fun wordScore(q: String, w: String): Float {
        if (w == q) return EXACT
        if (w.startsWith(q)) return PREFIX - 2f * (w.length - q.length) / w.length
        if (q.length >= 3 && w.contains(q)) return INSIDE
        // Digits must match exactly: "module 3" is not "module 4".
        if (q.any(Char::isDigit) || w.any(Char::isDigit)) return 0f
        val allowed = when {
            q.length < 4 -> 0
            q.length < 8 -> 1
            else -> 2
        }
        if (allowed == 0) return 0f
        // Against the whole word, or its start when the word is longer ("experment" in "experiments").
        val d = minOf(distance(q, w, allowed), if (w.length > q.length) distance(q, w.substring(0, q.length), allowed) else Int.MAX_VALUE)
        return if (d <= allowed) TYPO - d else 0f
    }

    /** Optimal string alignment distance (a swap counts once), giving up past [max]. */
    internal fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val n = b.length
        var prev2 = IntArray(n + 1)
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[n]
    }

    /** Lowercased runs of letters and digits, split where letters meet digits ("module5" → module, 5). */
    private fun words(s: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isLetterOrDigit()) {
                i++
                continue
            }
            val start = i
            val digit = s[i].isDigit()
            while (i < s.length && s[i].isLetterOrDigit() && s[i].isDigit() == digit) i++
            out += Word(s.substring(start, i).lowercase(), start)
        }
        return out
    }

    private fun normal(s: String) = words(s).joinToString(" ") { it.text }
}
