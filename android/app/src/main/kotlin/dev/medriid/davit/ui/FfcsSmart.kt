@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.medriid.davit.ui

import dev.medriid.davit.design.InkField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.PlanPick
import dev.medriid.davit.data.SmartResult
import dev.medriid.davit.data.SmartRules
import dev.medriid.davit.data.SmartSubject
import dev.medriid.davit.data.SmartTimetable
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.facultyKey
import dev.medriid.davit.data.findTimetables
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * FFCS's smart mode: the subjects you want, the faculty you'd take for each, how you like your
 * days; then the best timetables that fit, each a tap from your plan.
 */
@Composable
internal fun ColumnScope.SmartFfcs(semester: String, categories: List<Pair<String, String>>, onUse: (Int, List<PlanPick>) -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val setup = state.smart
    val subjects = setup.subjects
    val rules = setup.rules
    // Classes per subject as they arrive, so the cards can say how many slots and faculty.
    val loaded = remember { mutableStateMapOf<String, List<PlanPick>>().apply { putAll(repo.allocated) } }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SmartResult?>(null) }

    fun load(s: SmartSubject) = scope.launch {
        runCatching { repo.allottedClasses(s.code, s.title) }.onSuccess { loaded[s.code] = it }.onFailure { error = repo.explain(it) }
    }
    fun save(subs: List<SmartSubject> = subjects, r: SmartRules = rules) {
        repo.setSmart(setup.copy(subjects = subs, rules = r))
        result = null
    }

    // ---- subjects -----------------------------------------------------------------------
    GroupLabel("Your subjects" + if (subjects.isNotEmpty()) " · ${subjects.size}" else "")
    if (subjects.isEmpty()) InkText("Add the subjects you want next semester. daVIT tries every mix of their allotted slots.", style = RisoType.small)
    error?.let { ErrorNote(it) { error = null } }
    var openFaculty by remember { mutableStateOf<String?>(null) }
    subjects.forEach { s ->
        val classes = loaded[s.code]
        InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), kind = Kind.Classes, plateOffset = 3.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    InkText("${s.code} ${s.title}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                    InkText(
                        if (classes == null) "Reading its classes…"
                        else classes.groupBy { it.type }.entries.joinToString(" · ") { (type, list) ->
                            "${type.ifBlank { "Class" }}: ${list.map { it.slot }.distinct().size} slots, ${list.map { facultyKey(it.faculty) }.distinct().size} faculty"
                        }.ifBlank { "No classes allotted" },
                        style = RisoType.small,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clickable(role = Role.Button) { save(subjects - s) }) { InkChip("Remove") }
            }
            LaunchedEffect(s.code) { if (loaded[s.code] == null) load(s) }
            if (!classes.isNullOrEmpty()) {
                val names = classes.map { it.faculty }.filter { it.isNotBlank() }.distinctBy(::facultyKey).sorted()
                Box(Modifier.padding(top = 8.dp).clickable(role = Role.Button) { openFaculty = if (openFaculty == s.code) null else s.code }) {
                    InkChip(if (s.faculty.isEmpty()) "Any faculty · choose" else "${s.faculty.size} faculty you'd take · change", solid = s.faculty.isNotEmpty())
                }
                if (openFaculty == s.code) {
                    InkText("Tap the ones you'd take. None picked means any.", style = RisoType.small, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEach { n ->
                            val on = s.faculty.any { facultyKey(it) == facultyKey(n) }
                            Box(Modifier.clickable(role = Role.Checkbox) {
                                val next = if (on) s.faculty.filterNot { facultyKey(it) == facultyKey(n) }.toSet() else s.faculty + n
                                save(subjects.map { if (it.code == s.code) it.copy(faculty = next) else it })
                            }) { InkChip(n.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, solid = on) }
                        }
                    }
                }
            }
        }
    }
    AddSubjects(categories, subjects) { s -> save(subjects + s); load(s) }

    // ---- rules --------------------------------------------------------------------------
    Spacer(Modifier.height(18.dp))
    GroupLabel("How you like your days")
    RuleRow("Classes at 8:00 on at most", listOf("Any", "0 days", "1 day", "2 days", "3 days", "4 days"), rules.eightAmDays?.plus(1) ?: 0) { i ->
        save(r = rules.copy(eightAmDays = if (i == 0) null else i - 1))
    }
    val starts = listOf(8 * 60, 9 * 60, 10 * 60, 11 * 60, 14 * 60)
    RuleRow("No class before", starts.map(::clock), starts.indexOf(rules.earliest).coerceAtLeast(0)) { i -> save(r = rules.copy(earliest = starts[i])) }
    val ends = listOf(null, 13 * 60 + 20, 15 * 60 + 40, 17 * 60 + 30, 18 * 60 + 50)
    RuleRow("Done by", ends.map { it?.let(::clock) ?: "Any" }, ends.indexOf(rules.latest).coerceAtLeast(0)) { i -> save(r = rules.copy(latest = ends[i])) }
    RuleRow("Short lunch (a class right at 14:00) on at most", listOf("Any", "0 days", "1 day", "2 days", "3 days"), rules.shortLunchDays?.plus(1) ?: 0) { i ->
        save(r = rules.copy(shortLunchDays = if (i == 0) null else i - 1))
    }
    Spacer(Modifier.height(6.dp))
    ToggleRow("Try for days off", "Rank timetables with a whole weekday free first.", rules.daysOff) { save(r = rules.copy(daysOff = !rules.daysOff)) }
    ToggleRow("Fewer gaps", "Less waiting between classes on a day.", rules.fewGaps) { save(r = rules.copy(fewGaps = !rules.fewGaps)) }
    ToggleRow("Same faculty for theory and lab", "Where a course's slots allow it.", rules.sameFaculty) { save(r = rules.copy(sameFaculty = !rules.sameFaculty)) }

    // ---- find -----------------------------------------------------------------------------
    Spacer(Modifier.height(14.dp))
    InkButton(
        if (busy != null) busy!! else "Find timetables",
        {
            busy = "Reading every subject's classes"
            error = null
            scope.launch {
                runCatching {
                    subjects.forEach { s -> if (loaded[s.code] == null) loaded[s.code] = repo.allottedClasses(s.code, s.title) }
                    busy = "Trying every mix"
                    val options = subjects.associate { it.code to loaded[it.code].orEmpty() }
                    result = withContext(Dispatchers.Default) { findTimetables(subjects, options, rules) }
                }.onFailure { error = repo.explain(it) }
                busy = null
            }
        },
        enabled = subjects.isNotEmpty() && busy == null,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
    )
    when (val r = result) {
        null -> {}
        is SmartResult.None -> {
            Spacer(Modifier.height(12.dp))
            InkCard(Modifier.fillMaxWidth(), kind = Kind.Exams, plateOffset = 3.dp) {
                InkText("Nothing keeps every rule", style = RisoType.title)
                r.missing.forEach { InkText(it, style = RisoType.small, modifier = Modifier.padding(top = 4.dp)) }
                if (r.relax.isNotEmpty()) InkText("Loosen one of these and there are timetables: " + r.relax.joinToString(", ") + ".", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
            }
        }
        is SmartResult.Found -> {
            Spacer(Modifier.height(14.dp))
            GroupLabel("Best ${r.timetables.size} timetables")
            if (!r.complete) InkText("Searched for a few seconds and kept the best found; there may be others like them.", style = RisoType.small, modifier = Modifier.padding(bottom = 8.dp))
            r.timetables.forEachIndexed { i, t -> Option(i + 1, t, semester, Modifier.printIn(i)) { onUse(i + 1, t.picks) } }
        }
    }
    Spacer(Modifier.height(6.dp))
    InkText("Uses the classes VTOP has allotted to you. Registering still happens on VTOP; daVIT never registers.", style = RisoType.small)
}

/** One timetable found: what it's like, each day's spans, its classes, and Use this. */
@Composable
private fun Option(n: Int, t: SmartTimetable, semester: String, modifier: Modifier, onUse: () -> Unit) {
    val repo = LocalRepo.current
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("Option $n") }
    var saved by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }
    InkCard(modifier.fillMaxWidth().padding(bottom = 12.dp), kind = Kind.Classes, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText("Option $n", style = RisoType.title, modifier = Modifier.weight(1f))
            Box(Modifier.clickable(role = Role.Button) { naming = !naming }) { InkChip(if (saved != null) "Saved" else "Save") }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickable(role = Role.Button, onClick = onUse)) { InkChip("Use this", solid = true) }
        }
        if (naming) {
            Spacer(Modifier.height(8.dp))
            InkField(name, { name = it.take(40) }, "Save as", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            InkButton("Save", {
                repo.savePlan(name, semester, t.picks)
                saved = name.trim().ifEmpty { "Plan" }
                naming = false
            }, fillWidth = true, modifier = Modifier.fillMaxWidth())
        }
        saved?.let { InkText("Saved as \"$it\". It's under Saved plans at the top.", style = RisoType.small, modifier = Modifier.padding(top = 4.dp)) }
        InkText(facts(t), style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        (1..5).forEach { d ->
            val spans = spans(t.picks, d)
            Row(Modifier.padding(top = 3.dp)) {
                InkText(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.ENGLISH), style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(44.dp))
                InkText(if (spans.isEmpty()) "Off" else spans.joinToString(" · ") { (a, b) -> "${clock(a)}–${clock(b)}" }, style = RisoType.small)
            }
        }
        Box(Modifier.padding(top = 8.dp).clickable(role = Role.Button) { open = !open }) { InkChip(if (open) "Hide classes" else "Show classes") }
        if (open) t.picks.sortedBy { it.code }.forEach { p ->
            InkText("${p.code} ${p.type} · ${p.slot} · ${p.venue}", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
            InkText(p.faculty, style = RisoType.small)
        }
    }
}

/** "Fri off · 8:00 on 1 day · short lunch on 2 days · 3 h 20 waiting · done by 17:30". */
private fun facts(t: SmartTimetable): String = listOfNotNull(
    if (t.daysOff.isEmpty()) "No day off" else t.daysOff.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) } + " off",
    "8:00 on ${days(t.eightAmDays)}",
    "short lunch on ${days(t.shortLunchDays)}",
    if (t.gapMinutes == 0) "no waiting" else "${duration(t.gapMinutes)} waiting",
    if (t.splitFaculty > 0) "${t.splitFaculty} course${if (t.splitFaculty == 1) "" else "s"} with two faculty" else null,
    "done by ${clock(t.latestEnd)}",
).joinToString(" · ")

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

/** 200 → "3 h 20 min", 60 → "1 h", 45 → "45 min". */
private fun duration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** A day's classes as spans: classes a few minutes apart run together. */
private fun spans(picks: List<PlanPick>, day: Int): List<Pair<Int, Int>> {
    val ms = picks.flatMap { p -> p.meetings.filter { it.day == day } }.sortedBy { it.start }
    val out = mutableListOf<Pair<Int, Int>>()
    ms.forEach { m ->
        val last = out.lastOrNull()
        if (last != null && m.start - last.second <= 15) out[out.lastIndex] = last.first to maxOf(last.second, m.end) else out += m.start to m.end
    }
    return out
}

@Composable
private fun RuleRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    InkText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    ChoiceRow(options, selected, onPick)
}

/** Category, then its courses, each a tap to add. */
@Composable
private fun AddSubjects(categories: List<Pair<String, String>>, have: List<SmartSubject>, onAdd: (SmartSubject) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("") }
    val courses = remember { mutableStateMapOf<String, List<Pair<String, String>>>() }
    var loading by remember { mutableStateOf(false) }
    if (categories.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    InkText("Add from", label = true)
    Spacer(Modifier.height(4.dp))
    ChoiceRow(categories.map { it.first }, categories.indexOfFirst { it.first == category }) { i ->
        val id = categories[i].first
        category = if (category == id) "" else id
        if (category.isNotEmpty() && courses[id] == null) {
            loading = true
            scope.launch {
                runCatching { repo.onVtop { c -> c.allocationCourses(id) } }.onSuccess { courses[id] = it }
                loading = false
            }
        }
    }
    if (category.isEmpty()) return
    categories.firstOrNull { it.first == category }?.let { InkText(it.second.substringAfter(" - "), style = RisoType.small, modifier = Modifier.padding(top = 4.dp)) }
    val list = courses[category]
    if (list == null) {
        if (loading) Loading("Reading the courses")
        return
    }
    list.forEach { (code, label) ->
        val title = label.substringAfter(" - ", label)
        val added = have.any { it.code == code }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            InkText("$code $title", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickable(enabled = !added, role = Role.Button) { onAdd(SmartSubject(code, title)) }) { InkChip(if (added) "Added" else "Add", solid = !added) }
        }
    }
}
