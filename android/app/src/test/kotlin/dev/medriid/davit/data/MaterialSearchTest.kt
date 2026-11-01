package dev.medriid.davit.data

import dev.medriid.davit.search.Fuzzy
import dev.medriid.davit.vtop.CourseOption
import dev.medriid.davit.vtop.Material
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialSearchTest {
    private val theory = CourseOption("VL_A", "S1", "ETH", "Fall", "BXYZ101", "Applied Science", "Embedded Theory")
    private val lab = CourseOption("VL_A", "S1", "ELA", "Fall", "BXYZ101", "Applied Science", "Embedded Lab")
    private val other = CourseOption("VL_B", "S1", "TH", "Fall", "BXYZ202", "Data Structures", "Theory")

    private fun row(course: CourseOption, id: String, title: String, uploader: String, module: String = "1", mine: Boolean = false) =
        MaterialRow(course, Material(id, title, module, "Document", "", uploader, "", "01-08-2026", "Fall"), "", mine)

    private val rows = listOf(
        row(theory, "1", "Thermodynamics notes", "ASHA RAMAN", mine = true),
        row(theory, "2", "Battery chemistry", "Dr. Asha Raman", module = "2", mine = true),
        row(lab, "3", "Thermodynamics experiment", "VIKRAM SETH"),
        row(other, "4", "Trees and thermodynamics", "ASHA RAMAN"),
        row(other, "5", "Graphs", ""),
    )
    private val index = Fuzzy.Index(rows, MaterialsStore.SearchFields) { r -> listOf(r.material.title, "", r.material.uploader, "${r.course.code} ${r.course.name}") }

    private fun search(query: String = "", filter: MaterialFilter = MaterialFilter(), saved: Set<String> = emptySet()) = searchMaterials(index, query, filter, saved)
    private fun ids(r: MaterialResults) = r.hits.map { it.item.material.fileId }.toSet()

    @Test
    fun filtersCombineWithTheQuery() {
        assertEquals(setOf("1", "3", "4"), ids(search("thermodynamics")))
        assertEquals(setOf("1", "4"), ids(search("thermodynamics", MaterialFilter(faculty = "ASHA RAMAN"))))
        assertEquals(setOf("1"), ids(search("thermodynamics", MaterialFilter(subject = "BXYZ101", faculty = "ASHA RAMAN"))))
    }

    @Test
    fun aTitleOnTheFacultysNameIsOnePerson() {
        assertEquals(listOf(FilterOption("ASHA RAMAN", 3, mine = true), FilterOption("VIKRAM SETH", 1)), search().faculties)
    }

    @Test
    fun anEmbeddedCoursesLabIsItsOwnSubject() {
        assertEquals(listOf("BXYZ101", "BXYZ101 lab", "BXYZ202"), search().subjects.map { it.id })
        assertEquals(setOf("3"), ids(search(filter = MaterialFilter(subject = "BXYZ101 lab"))))
    }

    @Test
    fun optionsCountWhatTheOtherFiltersLeave() {
        val r = search("thermodynamics", MaterialFilter(subject = "BXYZ202"))
        assertEquals(listOf(FilterOption("ASHA RAMAN", 1)), r.faculties)
        assertEquals(listOf(1, 1, 1), r.subjects.map { it.count })
    }

    @Test
    fun aSetFilterStaysListedAtZero() {
        val r = search("graphs", MaterialFilter(faculty = "VIKRAM SETH"))
        assertEquals(0, r.total)
        assertEquals(listOf(FilterOption("VIKRAM SETH", 0)), r.faculties)
    }

    @Test
    fun yourFacultyAndSavedApplyToo() {
        assertEquals(setOf("1", "2"), ids(search(filter = MaterialFilter(mineOnly = true))))
        val saved = setOf(rows[3].key)
        assertEquals(setOf("4"), ids(search(filter = MaterialFilter(savedOnly = true), saved = saved)))
    }
}
