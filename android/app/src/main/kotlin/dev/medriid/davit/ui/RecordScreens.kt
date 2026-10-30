package dev.medriid.davit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.vtop.Allocation
import dev.medriid.davit.vtop.FormField
import dev.medriid.davit.vtop.Semester
import dev.medriid.davit.vtop.SimpleTable
import kotlinx.coroutines.launch

// Read-only records that are a dropdown, then a table: co/extra-curricular, QCM, course
// allocation. Fetched when opened; nothing here sends anything.

/** Each row of [table] as a card: the column named like [titleWords] as its title, the rest as label and value. */
@Composable
private fun TableCards(table: SimpleTable, titleWords: List<String>, kind: dev.medriid.davit.design.Kind, skip: List<String> = emptyList()) {
    val title = table.headers.indexOfFirst { h -> titleWords.any { h.contains(it, ignoreCase = true) } }.takeIf { it >= 0 } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        table.rows.forEachIndexed { i, row ->
            InkCard(Modifier.fillMaxWidth().printIn(i), kind = kind, plateOffset = 3.dp) {
                InkText(row.getOrElse(title) { "" }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
                table.headers.forEachIndexed { c, h ->
                    val v = row.getOrElse(c) { "" }
                    if (c == title || v.isBlank() || v == "-" || skip.any { h.contains(it, ignoreCase = true) }) return@forEachIndexed
                    Row(Modifier.padding(top = 4.dp)) {
                        InkText(h, style = RisoType.small, modifier = Modifier.width(118.dp).padding(end = 8.dp))
                        InkText(v, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

/** A semester picker built from VTOP's own list: one chip once chosen, the list on Change. */
@Composable
private fun SemesterPick(semesters: List<Semester>, chosen: String, onPick: (String) -> Unit) {
    FormFields(listOf(FormField("semester", "Semester", FormField.Kind.Choice, chosen, semesters.map { it.id to it.name })), mapOf("semester" to chosen)) { _, v -> onPick(v) }
}

/** Your co/extra-curricular record and the quality circle meetings for a semester, read only. */
@Composable
internal fun ActivitiesScreen(route: Route) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var semesters by remember { mutableStateOf<List<Semester>?>(null) }
    var semester by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<Pair<SimpleTable, SimpleTable>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(id: String) {
        semester = id
        data = null
        loading = true
        error = null
        scope.launch {
            runCatching { repo.onVtop { c -> c.activities(id) to runCatching { c.qcmSemesters(); c.qcm(id) }.getOrDefault(SimpleTable(emptyList(), emptyList())) } }
                .onSuccess { data = it }.onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    fun start() {
        loading = true
        error = null
        scope.launch {
            runCatching { repo.onVtop { c -> c.activitySemesters() } }
                .onSuccess { s -> semesters = s; s.firstOrNull()?.let { load(it.id) } ?: run { loading = false } }
                .onFailure { error = repo.explain(it); loading = false }
        }
    }
    LaunchedEffect(Unit) { if (semesters == null) start() }

    RouteScreen(route, "Activities and QCM", "Your co and extra-curricular record, and what quality circle meetings raised. Read only.", loading && data != null, {
        if (semester.isNotEmpty()) load(semester) else start()
    }) {
        error?.let { ErrorNote(it) { if (semester.isNotEmpty()) load(semester) else start() } }
        val s = semesters
        if (s == null) {
            if (loading) Loading("Reading VTOP's records")
            return@RouteScreen
        }
        SemesterPick(s, semester) { load(it) }
        val d = data
        if (d == null) {
            if (loading) Loading("Reading this semester")
            return@RouteScreen
        }
        GroupLabel("Co and extra-curricular")
        if (d.first.rows.isEmpty()) InkText("Nothing recorded this semester.", style = RisoType.small)
        else TableCards(d.first, listOf("Activity", "Event", "Title", "Name"), dev.medriid.davit.design.Kind.Events, skip = listOf("S.No", "Sl"))
        GroupLabel("Quality circle meetings")
        if (d.second.rows.isEmpty()) InkText("No meetings recorded this semester.", style = RisoType.small)
        else TableCards(d.second, listOf("Course Title", "Title"), dev.medriid.davit.design.Kind.People, skip = listOf("Sem Code", "Class Nbr"))
    }
}

/** Course allocation for next semester: a curriculum category, a course, then its classes (slot, venue, faculty). Read only. */
@Composable
internal fun AllocationScreen(route: Route) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Allocation?>(null) }
    var category by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var course by remember { mutableStateOf("") }
    var classes by remember { mutableStateOf<SimpleTable?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        loading = true
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = repo.explain(it) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (page == null) run { page = repo.onVtop { c -> c.allocation() } } }

    RouteScreen(route, "Course allocation", page?.semester?.ifBlank { null }?.let { "Classes allotted for $it. Read only: registering happens on VTOP." }
        ?: "Classes allotted for next semester. Read only: registering happens on VTOP.") {
        error?.let { ErrorNote(it, null) }
        val p = page
        if (p == null) {
            if (loading) Loading("Reading course allocation")
            return@RouteScreen
        }
        if (p.categories.isEmpty()) {
            InkText("VTOP shows no curriculum categories here.")
            return@RouteScreen
        }
        InkText("Category", label = true)
        Spacer(Modifier.height(4.dp))
        ChoiceRow(p.categories.map { it.first }, p.categories.indexOfFirst { it.first == category }) { i ->
            val id = p.categories[i].first
            category = id
            courses = null
            course = ""
            classes = null
            run { courses = repo.onVtop { c -> c.allocationCourses(id) } }
        }
        p.categories.firstOrNull { it.first == category }?.let { InkText(it.second.substringAfter(" - "), style = RisoType.small, modifier = Modifier.padding(top = 2.dp)) }
        val list = courses
        if (category.isNotEmpty() && list == null && loading) Loading("Reading the courses")
        if (list != null) {
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) InkText("No courses in this category for you.", style = RisoType.small)
            else FormFields(listOf(FormField("course", "Course", FormField.Kind.Choice, course, list)), mapOf("course" to course)) { _, v ->
                course = v
                classes = null
                run { classes = repo.onVtop { c -> c.allocationClasses(v) } }
            }
        }
        val t = classes
        if (course.isNotEmpty() && t == null && loading) Loading("Reading its classes")
        if (t != null) {
            GroupLabel("Classes")
            if (t.rows.isEmpty()) InkText("No classes allotted for this course yet.", style = RisoType.small)
            else TableCards(t, listOf("Slot"), dev.medriid.davit.design.Kind.Classes)
        }
    }
}
