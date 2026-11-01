package dev.medriid.davit.vtop

import dev.medriid.davit.vtop.Html.clean
import org.jsoup.Jsoup

/** Course allocation's page: the semester it's for ("Winter Semester 2026-27") and the curriculum categories. */
data class Allocation(val semester: String, val categories: List<Pair<String, String>>)

/** The classes you've registered for in a semester that hasn't started: your FFCS result. */
@kotlinx.serialization.Serializable
data class RegisteredClasses(val semester: Semester, val courses: List<TimetableCourse>)

/** Read-only record pages that are a dropdown and then a table: co/extra-curricular, QCM, course allocation. */
object Records {

    /** A page's `semesterSubId` dropdown (or `semSubId`), without its "-- Choose --" line or the " - VLR" suffix. */
    fun semesters(html: String): List<Semester> =
        (options(html, "semesterSubId").ifEmpty { options(html, "semSubId") }).map { (id, name) -> Semester(id, name.removeSuffix(" - VLR").trim()) }

    /**
     * [listed] with the semesters only [courses] knows about, those first. VTOP leaves next
     * semester out of its dropdowns until it starts, but the course page tags your courses with its id.
     */
    fun withCourseSemesters(listed: List<Semester>, courses: List<CourseOption>): List<Semester> {
        val ids = listed.map { it.id }.toSet()
        val unlisted = courses.filter { it.semesterId.isNotEmpty() && it.semesterId !in ids }
            .distinctBy { it.semesterId }.sortedByDescending { it.semesterId }
            .map { Semester(it.semesterId, it.semesterName) }
        return unlisted + listed
    }

    /** The newest of [semesters] after [currentId], if any. Ids of one length sort by date. */
    fun upcomingSemester(semesters: List<Semester>, currentId: String): Semester? =
        semesters.filter { it.id.length == currentId.length && it.id > currentId }.maxByOrNull { it.id }

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
