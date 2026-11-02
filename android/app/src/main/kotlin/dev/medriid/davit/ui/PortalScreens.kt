package dev.medriid.davit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.Section
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.vtop.SimpleTable
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Dated = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** A club's name in title case, with the short names as VTOP writes them: "Linux User's Group (LUG)". */
private fun directoryName(name: String): String =
    titleCase(name.lowercase())
        .replace(Regex("""\(([^()]+)\)""")) { "(" + it.groupValues[1].uppercase() + ")" }
        .replace(Regex("""\bVit\b"""), "VIT")

/** yyyy-MM-dd as "7 Sep 2026"; empty stays empty. */
private fun shown(date: String): String = runCatching { LocalDate.parse(date).format(Dated) }.getOrDefault(date)

private class Watched(val busy: Boolean, val error: String?, val refresh: () -> Unit)

/** The daily reading of circulars, projects and windows: fetched here if there's none yet, and on a pull. */
@Composable
private fun rememberWatched(): Watched {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val state by repo.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val refresh: () -> Unit = {
        scope.launch {
            busy = true
            error = null
            try {
                repo.refreshWatched()
            } catch (e: Exception) {
                error = repo.explain(e)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { if ((state.snapshot?.feeds?.watchAt ?: 0L) == 0L) refresh() }
    return Watched(busy, error, refresh)
}

/** The usual top of a [Section] screen: when it was read and what went wrong. False while there's nothing to show yet. */
@Composable
private fun SectionHead(section: Section, at: Long, loadingText: String): Boolean {
    val repo = LocalRepo.current
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    Freshness(at)
    errors[section]?.let { ErrorNote(it) { repo.campus.refresh(section, true) } }
    if (at == 0L && section in loading) {
        Loading(loadingText)
        return false
    }
    return true
}

// ---- circulars ----------------------------------------------------------------------------

@Composable
internal fun CircularsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val watched = rememberWatched()
    var query by rememberSaveable { mutableStateOf("") }
    val all = state.snapshot?.feeds?.circulars.orEmpty()
    RouteScreen(route, "Circulars", "Official PDFs from VTOP's circular portal. New ones reach you as alerts.", watched.busy && all.isNotEmpty(), watched.refresh) {
        watched.error?.let { ErrorNote(it, watched.refresh) }
        InkField(query, { query = it }, "Search circulars", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (all.isEmpty()) {
            if (watched.busy) Loading("Reading the circulars") else InkText("No circulars yet.", style = RisoType.small)
            return@RouteScreen
        }
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val found = remember(all, query) {
            all.filter { c -> words.all { w -> (c.title + " " + c.office + " " + c.group).lowercase().contains(w) } }.sortedByDescending { it.date }
        }
        SectionTitle(if (words.isEmpty()) "Newest first" else "${found.size} found")
        if (found.isEmpty()) InkText("Nothing matches \"$query\".", style = RisoType.small)
        val listed = found.take(60)
        if (listed.isNotEmpty()) {
            InkCard(Modifier.fillMaxWidth(), kind = Kind.People, plateOffset = 3.dp) {
                listed.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    FileRow("circ:${c.id}", c.title, listOf(shown(c.date), c.group).filter { it.isNotBlank() }.joinToString(" · "), fetch = { v, out, p -> v.downloadCircular(c.id, out, p) })
                }
            }
        }
        if (found.size > listed.size) InkText("Showing ${listed.size} of ${found.size}. Search to narrow it down.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- what's open --------------------------------------------------------------------------

@Composable
internal fun WindowsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val watched = rememberWatched()
    val windows = state.snapshot?.feeds?.windows.orEmpty().sortedBy { !it.open }
    RouteScreen(route, "What's open", "daVIT checks these once a day and tells you when one opens. You apply on VTOP.", watched.busy && windows.isNotEmpty(), watched.refresh) {
        Freshness(state.snapshot?.feeds?.watchAt ?: 0L)
        watched.error?.let { ErrorNote(it, watched.refresh) }
        if (windows.isEmpty()) {
            if (watched.busy) Loading("Checking VTOP") else InkText("Nothing read yet. Pull to check.", style = RisoType.small)
            return@RouteScreen
        }
        InkCard(Modifier.fillMaxWidth(), kind = Kind.People, plateOffset = 3.dp) {
            windows.forEachIndexed { i, w ->
                if (i > 0) Rule()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(w.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        if (w.note.isNotBlank()) InkText(w.note, style = RisoType.small, maxLines = 2)
                    }
                    Spacer(Modifier.width(8.dp))
                    InkChip(if (w.open) "Open" else "Closed", solid = w.open)
                }
            }
        }
    }
}

// ---- faculty projects ---------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val watched = rememberWatched()
    var query by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    val all = state.snapshot?.feeds?.projects.orEmpty()
    val followed = state.alertPrefs.projectAreas
    RouteScreen(route, "Faculty projects", "Research open to students. Ask to join on VTOP.", watched.busy && all.isNotEmpty(), watched.refresh) {
        watched.error?.let { ErrorNote(it, watched.refresh) }
        InkField(query, { query = it }, "Title, area or teacher", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        if (all.isEmpty()) {
            if (watched.busy) Loading("Reading the projects") else InkText("No projects yet.", style = RisoType.small)
            return@RouteScreen
        }
        val areas = remember(all) { all.map { it.area }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            areas.forEach { a ->
                Box(Modifier.clickable(role = Role.Button) { area = if (area == a) "" else a }) { InkChip(a, solid = area == a) }
            }
        }
        if (area.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val on = area in followed
            InkButton(if (on) "Stop alerts for $area" else "Alert me about new $area projects", {
                repo.setAlertPrefs(state.alertPrefs.copy(projectAreas = if (on) followed - area else followed + area))
            }, quiet = on)
        }
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val found = remember(all, query, area) {
            all.filter { p -> (area.isEmpty() || p.area == area) && words.all { w -> (p.title + " " + p.area + " " + p.faculty).lowercase().contains(w) } }
                .sortedByDescending { it.posted }
        }
        SectionTitle("${found.size} project${if (found.size == 1) "" else "s"}")
        val listed = found.take(40)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listed.forEach { p ->
                InkCard(Modifier.fillMaxWidth(), kind = Kind.People, plateOffset = 3.dp) {
                    InkText(p.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
                    InkText(listOf(p.area, shown(p.posted)).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                    InkText(listOf(personName(p.faculty.removePrefix("Dr.")), p.school).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                    FacultyRatingLink(null, p.faculty.removePrefix("Dr."), Modifier.padding(top = 6.dp))
                }
            }
        }
        if (found.size > listed.size) InkText("Showing ${listed.size} of ${found.size}. Search to narrow it down.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- rules and FAQ ------------------------------------------------------------------------

@Composable
internal fun RulesScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Rules) }
    RouteScreen(route, "Rules and FAQ", "VTOP's own words, kept for offline.", Section.Rules in loading && campus.rulesAt > 0, { repo.campus.refresh(Section.Rules, true) }) {
        InkCard(Modifier.fillMaxWidth(), kind = Kind.Vtop, plateOffset = 3.dp) {
            FileRow("rules:regulations", "Academic regulations", "PDF", fetch = { v, out, p -> v.downloadRegulations(out, p) })
        }
        Spacer(Modifier.height(10.dp))
        if (!SectionHead(Section.Rules, campus.rulesAt, "Reading the rules, this takes a moment")) return@RouteScreen
        ChoiceRow(listOf("FAQ", "Malpractice"), tab) { tab = it }
        Spacer(Modifier.height(10.dp))
        InkField(query, { query = it }, if (tab == 0) "Search the FAQ" else "Search the rules", Modifier.fillMaxWidth())
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (tab == 0) {
            val found = campus.faqs.filter { f -> words.all { w -> (f.question + " " + f.answer).lowercase().contains(w) } }
            if (found.isEmpty()) InkText("No answers here.", style = RisoType.small, modifier = Modifier.padding(top = 10.dp))
            found.groupBy { it.domain }.forEach { (domain, items) ->
                GroupLabel(titleCase(domain.lowercase()))
                InkCard(Modifier.fillMaxWidth(), kind = Kind.Vtop, plateOffset = 3.dp) {
                    items.take(40).forEachIndexed { i, f ->
                        if (i > 0) Rule()
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = if (open == f.question) "" else f.question }.padding(vertical = 6.dp)) {
                            InkText(f.question, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                            if (open == f.question) InkText(f.answer.ifBlank { "VTOP gives no answer." }, style = RisoType.small, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        } else {
            campus.malpractice.filter { r -> words.all { w -> (r.nature + " " + r.action).lowercase().contains(w) } }.groupBy { it.exam }.forEach { (exam, rules) ->
                GroupLabel(exam)
                InkCard(Modifier.fillMaxWidth(), kind = Kind.Vtop, plateOffset = 3.dp) {
                    rules.forEachIndexed { i, r ->
                        if (i > 0) Rule()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            InkText(r.nature, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                            InkText(r.action, style = RisoType.small, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- other exams --------------------------------------------------------------------------

/** Whatever columns VTOP gives, as label and value lines per row. */
@Composable
private fun TableRows(t: SimpleTable) {
    val keep = t.headers.indices.filterNot { t.headers[it] == "#" || t.headers[it].startsWith("S.No", ignoreCase = true) || t.headers[it].startsWith("Sl", ignoreCase = true) }
    InkCard(Modifier.fillMaxWidth(), kind = Kind.Exams, plateOffset = 3.dp) {
        t.rows.forEachIndexed { i, row ->
            if (i > 0) Rule()
            Column(Modifier.padding(vertical = 4.dp)) {
                keep.forEach { c ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        InkText(t.headers.getOrElse(c) { "" }, style = RisoType.small, modifier = Modifier.width(110.dp))
                        InkText(row.getOrElse(c) { "" }, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
internal fun OtherExamsScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.OtherExams) }
    RouteScreen(route, "Other exams", "Make-up, arrear, English proficiency and comprehensive, when you have any.", Section.OtherExams in loading && campus.otherExamsAt > 0, { repo.campus.refresh(Section.OtherExams, true) }) {
        if (!SectionHead(Section.OtherExams, campus.otherExamsAt, "Reading VTOP's exam pages")) return@RouteScreen
        if (campus.otherExams.isEmpty()) InkText("VTOP lists none of these for you.", style = RisoType.small)
        campus.otherExams.forEach { e ->
            GroupLabel(e.title)
            TableRows(e.table)
        }
    }
}

// ---- certificates -------------------------------------------------------------------------

@Composable
internal fun CertificatesScreen(route: Route) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Certificates) }
    RouteScreen(route, "Certificates", "graVITas, University Day and Six Sigma.", Section.Certificates in loading && campus.certificatesAt > 0, { repo.campus.refresh(Section.Certificates, true) }) {
        if (!SectionHead(Section.Certificates, campus.certificatesAt, "Looking for certificates")) return@RouteScreen
        if (campus.certificates.isEmpty()) InkText("No certificates on these pages yet.", style = RisoType.small)
        campus.certificates.groupBy { it.source }.forEach { (source, list) ->
            GroupLabel(source)
            InkCard(Modifier.fillMaxWidth(), kind = Kind.Events, plateOffset = 3.dp) {
                list.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    FileRow(c.key, titleCase(c.title.lowercase()), c.detail.ifBlank { null }, fetch = { v, out, p -> v.downloadCertificate(c, out, p) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        InkButton("Club event certificates", { nav.go(Route.Events) }, quiet = true)
    }
}

// ---- sports -------------------------------------------------------------------------------

@Composable
internal fun SportsScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Sports) }
    RouteScreen(route, "Sports and gym", "Fees include GST. Registering and paying stay on VTOP.", Section.Sports in loading && campus.sportsAt > 0, { repo.campus.refresh(Section.Sports, true) }) {
        if (!SectionHead(Section.Sports, campus.sportsAt, "Reading the facilities")) return@RouteScreen
        val sports = campus.sports ?: return@RouteScreen
        if (sports.mine.isNotEmpty()) {
            GroupLabel("Yours")
            InkCard(Modifier.fillMaxWidth(), kind = Kind.Events, plateOffset = 3.dp) {
                sports.mine.forEachIndexed { i, m ->
                    if (i > 0) Rule()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        InkText(m.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        InkText(m.status, style = RisoType.small)
                    }
                }
            }
        }
        GroupLabel("Facilities")
        if (sports.facilities.isEmpty()) InkText("VTOP lists none right now.", style = RisoType.small)
        else InkCard(Modifier.fillMaxWidth(), kind = Kind.Events, plateOffset = 3.dp) {
            sports.facilities.forEachIndexed { i, f ->
                if (i > 0) Rule()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(f.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        InkText("₹${f.fee}", style = RisoType.small)
                    }
                    InkChip("${f.seats} seats")
                }
            }
        }
    }
}

// ---- clubs --------------------------------------------------------------------------------

@Composable
internal fun ClubDirectoryScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.ClubDirectory) }
    RouteScreen(route, "All clubs", "Every club and chapter on VTOP. Joining stays on VTOP.", Section.ClubDirectory in loading && campus.clubDirectoryAt > 0, { repo.campus.refresh(Section.ClubDirectory, true) }) {
        if (!SectionHead(Section.ClubDirectory, campus.clubDirectoryAt, "Reading the clubs")) return@RouteScreen
        val d = campus.clubDirectory ?: return@RouteScreen
        if (d.mine.isNotEmpty()) {
            GroupLabel("Yours")
            InkCard(Modifier.fillMaxWidth(), kind = Kind.Events, plateOffset = 3.dp) {
                d.mine.forEachIndexed { i, c ->
                    if (i > 0) Rule()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        InkText(directoryName(c.name), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        if (c.status.isNotBlank()) InkChip(c.status)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        InkField(query, { query = it }, "Search ${d.all.size} clubs", Modifier.fillMaxWidth())
        val found = d.all.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val listed = found.take(60)
        Spacer(Modifier.height(10.dp))
        if (listed.isEmpty()) InkText("Nothing matches.", style = RisoType.small)
        else InkCard(Modifier.fillMaxWidth(), kind = Kind.Events, plateOffset = 3.dp) {
            listed.forEachIndexed { i, c ->
                if (i > 0) Rule()
                Column(Modifier.padding(vertical = 6.dp)) {
                    InkText(directoryName(c.name), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText(listOf(titleCase(c.type.lowercase()), c.status).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                }
            }
        }
        if (found.size > listed.size) InkText("Showing ${listed.size} of ${found.size}. Search to narrow it down.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- sign-ins -----------------------------------------------------------------------------

@Composable
internal fun SignInsScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.SignIns) }
    RouteScreen(
        route, "Sign-in activity",
        "Your VTOP logins for about three months. daVIT's look like the website's, and your IP changes as you move between networks, so a new IP alone isn't a worry.",
        Section.SignIns in loading && campus.signInsAt > 0, { repo.campus.refresh(Section.SignIns, true) },
    ) {
        if (!SectionHead(Section.SignIns, campus.signInsAt, "Reading your activity")) return@RouteScreen
        if (campus.signIns.isEmpty()) {
            InkText("VTOP lists none.", style = RisoType.small)
            return@RouteScreen
        }
        val listed = campus.signIns.take(60)
        InkCard(Modifier.fillMaxWidth(), kind = Kind.Vtop, plateOffset = 3.dp) {
            listed.forEachIndexed { i, s ->
                if (i > 0) Rule()
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkText(s.time, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    InkText(s.ip, style = RisoType.small)
                }
            }
        }
        if (campus.signIns.size > listed.size) InkText("Latest ${listed.size} of ${campus.signIns.size}.", style = RisoType.small, modifier = Modifier.padding(top = 8.dp))
    }
}
