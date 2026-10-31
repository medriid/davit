package dev.medriid.davit.ui

import dev.medriid.davit.data.summary
import dev.medriid.davit.design.InkField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.PlanPick
import dev.medriid.davit.data.allocationOptions
import dev.medriid.davit.data.asText
import dev.medriid.davit.data.clashes
import dev.medriid.davit.data.clashesIn
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.week
import dev.medriid.davit.data.with
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.vtop.Allocation
import dev.medriid.davit.vtop.Meeting
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * FFCS: next semester's timetable, planned before registration. Smart mode finds the best
 * timetables for your subjects and rules; by hand you pick a class for each course from
 * VTOP's course allocation, see the week it makes and any clash at once, and keep or share the
 * plan. Read only: registering happens on VTOP, and daVIT never registers.
 */
@Composable
internal fun PlannerScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inks = LocalInks.current
    var page by remember { mutableStateOf<Allocation?>(null) }
    var category by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    var options by remember { mutableStateOf<List<PlanPick>?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    // 0 Smart, 1 By hand; a plan already made opens by hand.
    var mode by rememberSaveable { mutableIntStateOf(if (repo.state.value.plan.second.isEmpty()) 0 else 1) }
    var note by remember { mutableStateOf<String?>(null) }

    fun run(what: String, block: suspend () -> Unit) {
        loading = what
        error = null
        scope.launch {
            runCatching { block() }.onFailure { error = repo.explain(it) }
            loading = null
        }
    }
    LaunchedEffect(Unit) { if (page == null) run("Reading course allocation") { page = repo.onVtop { c -> c.allocation() } } }

    val semester = page?.semester?.ifBlank { null } ?: state.plan.first
    // A plan made for another semester is kept until you start over, but said so.
    val picks = state.plan.second
    val clashes = picks.clashes()

    RouteScreen(route, "FFCS", "Plan ${semester.ifBlank { "next semester" }}: Smart finds timetables that fit your rules, or pick each class by hand.") {
        error?.let { ErrorNote(it) { error = null; run("Reading course allocation") { page = repo.onVtop { c -> c.allocation() } } } }

        // Plans kept under a name: open one into By hand, or delete it (a second tap confirms).
        if (state.savedPlans.isNotEmpty()) {
            GroupLabel("Saved plans · ${state.savedPlans.size}")
            var deleting by remember { mutableStateOf<Long?>(null) }
            state.savedPlans.sortedByDescending { it.id }.forEach { sp ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(sp.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                        InkText(sp.picks.summary(), style = RisoType.small, maxLines = 2)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clickable(role = Role.Button) {
                        repo.setPlan(sp.semester, sp.picks)
                        note = "Opened \"${sp.name}\". Changes here don't touch the saved copy until you save again."
                        mode = 1
                    }) { InkChip("Open", solid = true) }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clickable(role = Role.Button) {
                        if (deleting == sp.id) { repo.deleteSavedPlan(sp.id); deleting = null } else deleting = sp.id
                    }) { InkChip(if (deleting == sp.id) "Sure?" else "Delete") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        ChoiceRow(listOf("Smart", "By hand"), mode) { mode = it }
        Spacer(Modifier.height(14.dp))
        if (mode == 0) {
            val p = page
            if (p == null) {
                loading?.let { Loading(it) }
                return@RouteScreen
            }
            SmartFfcs(p.semester, p.categories) { n, chosen ->
                repo.setPlan(p.semester, chosen)
                note = "Option $n is now your plan. Change any class below."
                mode = 1
            }
            return@RouteScreen
        }
        note?.let {
            InkText(it, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
        }

        if (picks.isNotEmpty()) {
            val courseCount = picks.distinctBy { it.code }.size
            InkText("$courseCount course${if (courseCount == 1) "" else "s"} · ${if (clashes.isEmpty()) "no clashes" else "${clashes.size} clash${if (clashes.size == 1) "" else "es"}"}", style = RisoType.title)
            if (state.plan.first.isNotBlank() && page != null && state.plan.first != page?.semester) {
                InkText("Made for ${state.plan.first}.", style = RisoType.small)
            }
            Spacer(Modifier.height(10.dp))
            if (clashes.isNotEmpty()) {
                InkCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), kind = Kind.Exams, plate = inks.a, plateOffset = 3.dp) {
                    InkText("Clashes", label = true)
                    clashes.forEach { c ->
                        InkText("${dayName(c.day)} ${clock(c.start)}: ${c.a.code} ${c.a.slot} and ${c.b.code} ${c.b.slot}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            GroupLabel("Your week")
            val clashing = clashes.flatMap { listOf(it.a, it.b) }.toSet()
            picks.week().entries.forEachIndexed { i, (day, list) ->
                InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).printIn(i), kind = Kind.Classes, plateOffset = 3.dp) {
                    InkText(dayName(day, full = true), style = RisoType.title)
                    list.forEach { (m, p) ->
                        Row(Modifier.padding(top = 6.dp)) {
                            InkText("${clock(m.start)}–${clock(m.end)}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(96.dp))
                            InkText(
                                "${p.code} · ${p.venue.ifBlank { m.slot }}" + if (p in clashing) "  ✕ clash" else "",
                                style = RisoType.small.copy(fontWeight = if (p in clashing) FontWeight.Bold else FontWeight.Normal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            GroupLabel("Your classes")
            picks.sortedBy { it.code }.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText("${p.code} ${p.title}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                        InkText(listOf(p.type, p.slot, p.venue, p.faculty).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 2)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clickable(role = Role.Button) { repo.setPlan(semester, picks - p) }) { InkChip("Remove") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Share", { shareText(context, "Timetable plan", picks.asText(semester)) }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
                InkButton(if (clearing) "Yes, start over" else "Start over", {
                    if (clearing) { repo.setPlan(semester, emptyList()); clearing = false } else clearing = true
                }, quiet = !clearing, modifier = Modifier.weight(1f), fillWidth = true)
            }
            Spacer(Modifier.height(10.dp))
            var naming by remember { mutableStateOf(false) }
            var name by remember { mutableStateOf("") }
            if (!naming) {
                InkButton("Save as…", { naming = true }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
            } else {
                InkField(name, { name = it.take(40) }, "Name this plan", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                InkButton("Save", {
                    repo.savePlan(name, semester, picks)
                    note = "Saved as \"${name.trim().ifEmpty { "Plan" }}\"."
                    naming = false
                    name = ""
                }, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(22.dp))
        }

        GroupLabel("Add a course")
        val p = page
        if (p == null) {
            loading?.let { Loading(it) }
            return@RouteScreen
        }
        if (p.categories.isEmpty()) {
            InkText("VTOP shows no course allocation yet. It opens a little before registration.")
            return@RouteScreen
        }
        ChoiceRow(p.categories.map { it.first }, p.categories.indexOfFirst { it.first == category }) { i ->
            val id = p.categories[i].first
            category = id
            courses = null
            open = null
            options = null
            run("Reading the courses") { courses = repo.onVtop { c -> c.allocationCourses(id) } }
        }
        p.categories.firstOrNull { it.first == category }?.let { InkText(it.second.substringAfter(" - "), style = RisoType.small, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        if (category.isEmpty()) {
            InkText("Pick a category to see its courses.", style = RisoType.small)
            return@RouteScreen
        }
        val list = courses
        if (list == null) {
            loading?.let { Loading(it) }
            return@RouteScreen
        }
        if (list.isEmpty()) InkText("No courses allotted in this category.", style = RisoType.small)
        list.forEach { (code, label) ->
            val title = label.substringAfter(" - ", label)
            val isOpen = open?.first == code
            val chosen = picks.filter { it.code == code }
            InkCard(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                onClick = {
                    if (isOpen) { open = null; options = null } else {
                        open = code to title
                        options = null
                        run("Reading the classes") { options = allocationOptions(repo.onVtop { c -> c.allocationClasses(code) }, code, title) }
                    }
                },
                kind = Kind.Classes,
                plateOffset = 3.dp,
            ) {
                InkText("$code $title", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(if (chosen.isEmpty()) "Not in your plan · tap for its classes" else "In your plan: " + chosen.joinToString(", ") { it.slot }, style = RisoType.small)
                if (isOpen) {
                    val opts = options
                    when {
                        opts == null -> loading?.let { Box(Modifier.padding(top = 8.dp)) { Loading(it) } }
                        opts.isEmpty() -> InkText("VTOP lists no classes for this course yet.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
                        else -> opts.groupBy { it.type }.forEach { (type, group) ->
                            InkText(type.ifBlank { "Classes" }, label = true, modifier = Modifier.padding(top = 12.dp))
                            group.forEach { o ->
                                val picked = picks.any { it == o }
                                val against = o.clashesIn(picks)
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).clickable(role = Role.Button) {
                                        repo.setPlan(p.semester, if (picked) picks - o else picks.with(o))
                                    },
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            InkText("${o.slot} · ${o.venue}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                                            InkText(o.faculty, style = RisoType.small, maxLines = 1)
                                            InkText(times(o.meetings), style = RisoType.small, maxLines = 2)
                                            if (against.isNotEmpty()) InkText("Clashes with " + against.joinToString(", ") { "${it.code} ${it.slot}" }, style = RisoType.small.copy(color = inks.a, fontWeight = FontWeight.SemiBold))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        InkChip(if (picked) "✓ Picked" else "Pick", solid = picked)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Mon 8:00 · Wed 9:00 · Fri 10:00", the times a slot meets. */
private fun times(meetings: List<Meeting>): String =
    meetings.sortedWith(compareBy({ it.day }, { it.start })).joinToString(" · ") { "${dayName(it.day)} ${clock(it.start)}" }.ifBlank { "Times not in the slot table" }

private fun dayName(day: Int, full: Boolean = false): String =
    runCatching { DayOfWeek.of(day).getDisplayName(if (full) TextStyle.FULL else TextStyle.SHORT, Locale.ENGLISH) }.getOrDefault("Day $day")
