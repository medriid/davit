package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import org.jsoup.Jsoup

/** Course allocation's page: the semester it's for ("Winter Semester 2026-27") and the curriculum categories. */
data class Allocation(val semester: String, val categories: List<Pair<String, String>>)

/** Read-only record pages that are a dropdown and then a table: co/extra-curricular, QCM, course allocation. */
object Records {

    /** A page's `semesterSubId` dropdown (or `semSubId`), without its "-- Choose --" line or the " - VLR" suffix. */
    fun semesters(html: String): List<Semester> =
        (options(html, "semesterSubId").ifEmpty { options(html, "semSubId") }).map { (id, name) -> Semester(id, name.removeSuffix(" - VLR").trim()) }

    /** A `select`'s options by its name or id, value → text, the empty "-- Choose --" one left out. */
    fun options(html: String, select: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html)
        val el = doc.selectFirst("select[name=$select]") ?: doc.getElementById(select) ?: return emptyList()
        return el.select("option").mapNotNull { o ->
            val v = o.attr("value").trim()
            if (v.isEmpty()) null else v to clean(o.text())
        }
    }

    /** `academics/common/StudentRegistrationScheduleAllocation`: "Semester Winter Semester 2026-27" and the `curriculumCategory` choices. */
    fun allocation(html: String): Allocation {
        val text = clean(Jsoup.parse(html).text())
        val semester = Regex("""Semester\s*((?:Fall|Winter|Summer|Tri|Inter)[A-Za-z &]*?\d{4}-\d{2})""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return Allocation(semester, options(html, "curriculumCategory"))
    }
}
