package dev.medriid.davit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Section
import dev.medriid.davit.data.sessionGpas
import dev.medriid.davit.data.sizeText
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.vtop.Person
import dev.medriid.davit.vtop.VtopMessage
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One tile on the campus hub. */
private data class Tile(val route: Route?, val title: String, val note: String, val kind: dev.medriid.davit.design.Kind, val open: (() -> Unit)? = null)

/** Everything else VTOP shows, one tile each, in sections. Read only, except what you review and send yourself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusHub(route: Route) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val snap = state.snapshot
    val sections = listOf(
        "Plan" to listOf(
            Tile(Route.ExamBoard, "Exam board", "Every paper, room and seat", dev.medriid.davit.design.Kind.Exams),
            Tile(Route.Planner, "FFCS", "Next semester's timetable, smart or by hand", dev.medriid.davit.design.Kind.Classes),
            Tile(Route.Friends, "Free with friends", "Swap weeks by QR code", dev.medriid.davit.design.Kind.People),
            Tile(Route.Corrections, "Attendance corrections", "Absent by mistake? Keep track", dev.medriid.davit.design.Kind.Attendance),
        ),
        "News and people" to listOf(
            Tile(Route.Messages, "Messages", (campus.classMessages.size + campus.proctorMessages.size).let { if (it > 0) "$it from class and proctor" else "Class and proctor" }, dev.medriid.davit.design.Kind.People),
            Tile(Route.Spotlight, "Spot-light", if (campus.spotlight.isNotEmpty()) "${campus.spotlight.size} notices" else "VTOP's notices and files", dev.medriid.davit.design.Kind.People),
            Tile(Route.Events, "Club events", "Register, certificates", dev.medriid.davit.design.Kind.Events),
            Tile(Route.People, "Proctor, HOD, dean", campus.proctor?.name?.let(::titleCase) ?: "Who to ask", dev.medriid.davit.design.Kind.People),
        ),
        "Requests" to listOfNotNull(
            Tile(Route.Hostel, "Hostel", "Leave, night slips, mess", dev.medriid.davit.design.Kind.Vtop).takeIf { snap?.profile?.hostel != null },
            Tile(Route.Bonafide, "Bonafide", "Apply for a certificate", dev.medriid.davit.design.Kind.Vtop),
            Tile(Route.CourseFeedback, "Course feedback", "To your teachers, any time", dev.medriid.davit.design.Kind.Vtop),
            Tile(Route.PaperSeeing, "Paper seeing", "Or re-evaluation, after results", dev.medriid.davit.design.Kind.Vtop),
        ),
        "Records" to listOf(
            Tile(Route.SemesterGrades, "Semester GPA", "Each term's results", dev.medriid.davit.design.Kind.Exams),
            Tile(Route.Curriculum, "Curriculum", "Credits and syllabus PDFs", dev.medriid.davit.design.Kind.Classes),
            Tile(Route.Allocation, "Course allocation", "Next semester's classes", dev.medriid.davit.design.Kind.Classes),
            Tile(Route.Activities, "Activities and QCM", "Co-curricular, class meetings", dev.medriid.davit.design.Kind.Events),
            Tile(Route.Money, "Receipts and dues", "Fees letters, library", dev.medriid.davit.design.Kind.Vtop),
            Tile(Route.Gate, "Gate log", "Biometric in and out", dev.medriid.davit.design.Kind.Vtop),
        ),
        "Files" to listOf(
            Tile(Route.Search, "Search materials", "Across every course", dev.medriid.davit.design.Kind.Assignments),
            Tile(null, "Scan to PDF", "Pages from the camera", dev.medriid.davit.design.Kind.Assignments) { repo.scanner.open(dev.medriid.davit.scan.ScanTarget()) },
            Tile(Route.Downloads, "Downloads", "Saved on this phone", dev.medriid.davit.design.Kind.Assignments),
        ),
    )
    RouteScreen(route, "More from VTOP", "Read only, except what you review and send yourself. Nothing here ever pays.") {
        var n = 0
        sections.forEach { (name, tiles) ->
            GroupLabel(name, Modifier.padding(bottom = 4.dp))
            tiles.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { t ->
                        InkCard(
                            Modifier.weight(1f).fillMaxHeight().then(t.route?.let { Modifier.sharedCard(it.sharedKey) } ?: Modifier).printIn(n++),
                            onClick = { t.open?.invoke() ?: t.route?.let(nav::go) },
                            kind = t.kind,
                            plateOffset = 4.dp,
                        ) {
                            InkText(t.title, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 2)
                            Spacer(Modifier.height(6.dp))
                            InkText(t.note, style = RisoType.small, maxLines = 2)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ---- messages ----------------------------------------------------------------------------

@Composable
internal fun MessagesScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    val state by repo.state.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Messages) }
    RouteScreen(route, "Messages", "From your class teachers and your proctor. VTOP deletes them after a while.", Section.Messages in loading && campus.messagesAt > 0, { repo.campus.refresh(Section.Messages, true) }) {
        Freshness(campus.messagesAt)
        errors[Section.Messages]?.let { ErrorNote(it) { repo.campus.refresh(Section.Messages, true) } }
        state.snapshot?.feeds?.proctorNote?.let {
            GroupLabel("On the home page")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.People, plateOffset = 3.dp) { InkText(it) }
        }
        if (campus.messagesAt == 0L && Section.Messages in loading) {
            Loading("Reading messages")
            return@RouteScreen
        }
        GroupLabel("Class messages")
        MessageList(campus.classMessages, "No class messages right now.")
        GroupLabel("Proctor messages")
        MessageList(campus.proctorMessages, "No messages from your proctor this week.")
    }
}

@Composable
private fun MessageList(list: List<VtopMessage>, empty: String) {
    if (list.isEmpty()) {
        InkText(empty, style = RisoType.small)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEachIndexed { i, m ->
            InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.People, plateOffset = 3.dp) {
                val head = listOf(titleCase(m.from), m.course).filter { it.isNotBlank() }.joinToString(" · ")
                if (head.isNotEmpty()) InkText(head, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                InkText(m.text)
                if (m.date.isNotBlank()) InkText(m.date, style = RisoType.small)
            }
        }
    }
}

// ---- spot-light --------------------------------------------------------------------------

@Composable
internal fun SpotlightScreen(route: Route) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Spotlight) }
    RouteScreen(route, "Spot-light", "VTOP's notices. Files download here; links open in your browser.", Section.Spotlight in loading && campus.spotlightAt > 0, { repo.campus.refresh(Section.Spotlight, true) }) {
        Freshness(campus.spotlightAt)
        errors[Section.Spotlight]?.let { ErrorNote(it) { repo.campus.refresh(Section.Spotlight, true) } }
        if (campus.spotlight.isEmpty()) {
            if (Section.Spotlight in loading) Loading("Reading Spot-light") else InkText("Nothing in Spot-light right now.")
            return@RouteScreen
        }
        campus.spotlight.groupBy { it.section }.forEach { (section, items) ->
            GroupLabel(section.ifBlank { "Notices" })
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.People, plateOffset = 3.dp) {
                items.forEachIndexed { i, s ->
                    if (i > 0) Rule()
                    val path = s.download
                    if (path != null) {
                        FileRow("spot:" + path.substringAfterLast('/'), s.title, "File", fetch = { c, out, p -> c.downloadSpotlight(path, out, p) })
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                InkText(s.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
                                InkText(Uri.parse(s.link).host.orEmpty(), style = RisoType.small, maxLines = 1)
                            }
                            Spacer(Modifier.width(10.dp))
                            InkChip("Link")
                        }
                    }
                }
            }
        }
    }
}

// ---- people ------------------------------------------------------------------------------

@Composable
internal fun PeopleScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.People) }
    RouteScreen(route, "Proctor, HOD, dean", null, Section.People in loading && campus.peopleAt > 0, { repo.campus.refresh(Section.People, true) }) {
        Freshness(campus.peopleAt)
        errors[Section.People]?.let { ErrorNote(it) { repo.campus.refresh(Section.People, true) } }
        if (campus.peopleAt == 0L && Section.People in loading) {
            Loading("Reading your proctor's details")
            return@RouteScreen
        }
        campus.proctor?.let { PersonCard(it, 0) } ?: InkText("VTOP didn't list a proctor.", style = RisoType.small)
        campus.hodDean.forEachIndexed { i, p ->
            Spacer(Modifier.height(14.dp))
            PersonCard(p, i + 1)
        }
    }
}

@Composable
internal fun PersonCard(p: Person, index: Int) {
    val context = LocalContext.current
    InkCard(Modifier.fillMaxWidth().printIn(index), kind = dev.medriid.davit.design.Kind.People, plateOffset = 4.dp) {
        InkText(p.role, label = true)
        Spacer(Modifier.height(6.dp))
        InkText(titleCase(p.name.removePrefix("Dr. ").removePrefix("DR. ")).let { if (p.name.startsWith("Dr", true)) "Dr. $it" else it }, style = RisoType.title)
        if (p.designation.isNotBlank()) InkText(p.designation, style = RisoType.small)
        val rest = p.fields.filter { (k, v) ->
            v.isNotBlank() && listOf("name", "designation", "mail", "cabin").none { k.contains(it, ignoreCase = true) }
        }
        rest.forEach { (k, v) ->
            Row(Modifier.padding(top = 4.dp)) {
                InkText(k.removePrefix("Faculty ").replaceFirstChar { it.uppercase() }, style = RisoType.small, modifier = Modifier.width(120.dp))
                InkText(v, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.cabin.isNotBlank()) InkChip("Cabin ${p.cabin}")
            if (p.email.isNotBlank()) {
                Box(Modifier.clickable(role = Role.Button) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + p.email)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { InkChip(p.email, solid = true) }
            }
        }
    }
}

// ---- money -------------------------------------------------------------------------------

@Composable
internal fun MoneyScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Money) }
    RouteScreen(route, "Receipts and dues", "Read only: daVIT never pays anything. Pay on VTOP.", Section.Money in loading && campus.moneyAt > 0, { repo.campus.refresh(Section.Money, true) }) {
        Freshness(campus.moneyAt)
        errors[Section.Money]?.let { ErrorNote(it) { repo.campus.refresh(Section.Money, true) } }
        if (campus.moneyAt == 0L && Section.Money in loading) {
            Loading("Reading receipts")
            return@RouteScreen
        }
        if (campus.library.isNotEmpty()) {
            GroupLabel("Library dues")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 3.dp) {
                campus.library.forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        InkText(l.label, modifier = Modifier.weight(1f), style = if (l.label.contains("total", true)) RisoType.body.copy(fontWeight = FontWeight.SemiBold) else RisoType.body)
                        InkText(l.amount?.let { "₹ " + "%.2f".format(Locale.ENGLISH, it) } ?: "–", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        if (campus.feeLetters.isNotEmpty()) {
            GroupLabel("Fees letters")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 3.dp) {
                campus.feeLetters.forEachIndexed { i, f ->
                    if (i > 0) Rule()
                    FileRow(
                        "fee:${f.batchId}", f.description.lowercase().replaceFirstChar { it.uppercase() },
                        "Year ${f.year} · term ${f.term}",
                        fetch = { c, out, p -> c.downloadFeeLetter(f.batchId, out, p) },
                    )
                }
            }
        }
        GroupLabel("Receipts")
        if (campus.receipts.isEmpty()) InkText("No receipts on VTOP.", style = RisoType.small)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            campus.receipts.forEachIndexed { i, r ->
                InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 3.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(r.date, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                            InkText("Receipt ${r.number} · invoice ${r.invoice}", style = RisoType.small, maxLines = 2)
                        }
                        Heading(r.amount?.let { "₹" + fmtNum(it) } ?: "–", 22.sp)
                    }
                }
            }
        }
    }
}

// ---- gate log ----------------------------------------------------------------------------

@Composable
internal fun GateScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    var days by rememberSaveable { mutableIntStateOf(repo.campus.gateDays) }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Gate) }
    RouteScreen(route, "Gate log", "Your biometric entries and exits, from VTOP.", Section.Gate in loading && campus.gateAt > 0, { repo.campus.refresh(Section.Gate, true) }) {
        ChoiceRow(listOf("Today", "7 days", "30 days"), listOf(0, 7, 30).indexOf(days)) {
            days = listOf(0, 7, 30)[it]
            repo.campus.gateDays = days
            repo.campus.refresh(Section.Gate, true)
        }
        Spacer(Modifier.height(10.dp))
        Freshness(campus.gateAt)
        errors[Section.Gate]?.let { ErrorNote(it) { repo.campus.refresh(Section.Gate, true) } }
        val t = campus.gate
        if (t == null || t.rows.isEmpty()) {
            if (Section.Gate in loading) Loading("Reading the gate log") else InkText("No entries since ${campus.gateFrom.ifBlank { "then" }}.")
            return@RouteScreen
        }
        // Whatever columns VTOP gives, as label/value pairs per entry (the serial number dropped).
        val keep = t.headers.indices.filterNot { t.headers[it].contains("sl", ignoreCase = true) || t.headers[it] == "#" }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            t.rows.forEachIndexed { i, row ->
                InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.Vtop, plateOffset = 3.dp) {
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
}

// ---- curriculum ---------------------------------------------------------------------------

@Composable
internal fun CurriculumScreen(route: Route) {
    val repo = LocalRepo.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Curriculum) }
    RouteScreen(route, "Curriculum", "Credits by category, and each course's syllabus.", Section.Curriculum in loading && campus.curriculumAt > 0, { repo.campus.refresh(Section.Curriculum, true) }) {
        Freshness(campus.curriculumAt)
        errors[Section.Curriculum]?.let { ErrorNote(it) { repo.campus.refresh(Section.Curriculum, true) } }
        InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Classes, plateOffset = 3.dp) {
            FileRow("cur", "Your curriculum (PDF)", "As VTOP prints it", fetch = { c, out, p -> c.downloadCurriculum(out, p) })
        }
        if (campus.categories.isEmpty()) {
            if (Section.Curriculum in loading) Loading("Reading the curriculum") else InkText("No curriculum on VTOP.", modifier = Modifier.padding(top = 12.dp))
            return@RouteScreen
        }
        val total = campus.categories.sumOf { it.max ?: 0.0 }
        GroupLabel("Credits" + if (total > 0) " · ${fmtNum(total)} in all" else "")
        InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Classes, plateOffset = 3.dp) {
            campus.categories.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row {
                    InkText("${c.id} · ${c.name}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f), maxLines = 2)
                    InkText("${c.credits?.let(::fmtNum) ?: "–"} / ${c.max?.let(::fmtNum) ?: "–"}", style = RisoType.small)
                }
                Spacer(Modifier.height(4.dp))
                val max = c.max ?: 0.0
                HalftoneBar(if (max > 0) ((c.credits ?: 0.0) / max).toFloat() else 1f, height = 8.dp)
            }
        }
        InkText("\"Credit\" is what VTOP counts towards each category; check Grades for what you've earned.", style = RisoType.small.copy(color = LocalInks.current.rule), modifier = Modifier.padding(top = 6.dp))
        campus.curriculum.groupBy { it.group }.forEach { (group, list) ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open = if (open == group) null else group }.padding(top = 16.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkText(group.ifBlank { "Courses" }, label = true, modifier = Modifier.weight(1f))
                InkChip(if (open == group) "Hide" else "${list.size} courses", solid = open != group)
            }
            if (open == group) {
                InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Classes, plateOffset = 3.dp) {
                    list.forEachIndexed { i, c ->
                        if (i > 0) Rule()
                        FileRow(
                            "syl:${c.code}", c.title.ifBlank { c.code },
                            listOfNotNull(c.code, c.type.ifBlank { null }, c.credits?.let { "${fmtNum(it)} credits" }, c.ltpj.ifBlank { null }?.let { "L-T-P-J $it" }).joinToString(" · "),
                            fetch = { cl, out, p -> cl.downloadSyllabus(c.code, out, p) },
                        )
                    }
                }
            }
        }
    }
}

// ---- semester GPA ------------------------------------------------------------------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SemesterGradesScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    val snap = state.snapshot
    val sessions = remember(snap?.grades) { snap?.grades.sessionGpas() }
    var semester by rememberSaveable { mutableStateOf<String?>(null) }
    RouteScreen(route, "Semester GPA", "From your grade history; tap a semester for VTOP's own results page.") {
        if (sessions.isEmpty()) {
            InkText(if (snap?.grades == null) "Grades arrive with the next sync." else "No graded semesters yet. They appear after your first results.", style = RisoType.small)
        } else {
            val hero = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toSp() }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sessions.reversed().forEachIndexed { i, s ->
                    InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.Exams, plateOffset = 4.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                InkText(s.session, style = RisoType.title)
                                InkText("${fmtNum(s.credits)} graded credits", style = RisoType.small)
                            }
                            // Cut, not rounded: 8.996 must not read as 9.00.
                            Overprint("%.2f".format(Locale.ENGLISH, Math.floor(s.gpa * 100) / 100), hero, registerKey = s.gpa)
                        }
                    }
                }
            }
        }
        val sems = snap?.semesters.orEmpty()
        if (sems.isNotEmpty()) {
            GroupLabel("VTOP's results page")

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sems.forEach { s ->
                    Box(Modifier.clickable(role = Role.RadioButton) {
                        semester = s.id
                        repo.campus.loadSemester(s.id)
                    }) { InkChip(s.name.removeSuffix(" - VLR"), solid = semester == s.id) }
                }
            }
            Spacer(Modifier.height(12.dp))
            errors[Section.Grades]?.let { ErrorNote(it) { semester?.let { repo.campus.loadSemester(it, force = true) } } }
            val g = semester?.let { campus.semesterGrades[it] }
            when {
                semester == null -> {}
                g == null && Section.Grades in loading -> Loading("Reading that semester's results")
                g == null -> {}
                g.courses.isEmpty() && g.gpa == null -> InkText("VTOP has no results for that semester.", style = RisoType.small)
                else -> InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Exams, plateOffset = 3.dp) {
                    g.gpa?.let {
                        InkText("GPA ${"%.2f".format(Locale.ENGLISH, Math.floor(it * 100) / 100)}", style = RisoType.title)
                        Spacer(Modifier.height(8.dp))
                    }
                    g.courses.forEachIndexed { i, e ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                InkText(e.courseTitle.ifBlank { e.courseCode }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                                InkText("${e.courseCode} · ${fmtNum(e.credits)} credits", style = RisoType.small)
                            }
                            InkChip(e.grade ?: "–", solid = e.grade != null)
                        }
                    }
                }
            }
        }
    }
}

// ---- downloads ---------------------------------------------------------------------------

@Composable
internal fun DownloadsScreen(route: Route) {
    val repo = LocalRepo.current
    val saved by repo.files.saved.collectAsState()
    val haptics = rememberHaptics()
    var sure by remember { mutableStateOf(false) }
    val total = remember(saved) { saved.values.sumOf { it.length() } }
    RouteScreen(route, "Downloads", "${saved.size} file${if (saved.size == 1) "" else "s"} · ${sizeText(total)}. They open without a connection.") {
        if (saved.isEmpty()) {
            InkText("Nothing saved yet. Materials, question papers, letters and syllabi you open are kept here.")
            return@RouteScreen
        }
        val kinds = mapOf("mat" to "Course materials", "qp" to "Question papers", "da" to "Your submissions", "spot" to "Spot-light", "syl" to "Syllabi", "cur" to "Curriculum", "fee" to "Fees letters", "scan" to "Your scans")
        saved.entries.groupBy { it.key.substringBefore(':') }.toSortedMap().forEach { (kind, list) ->
            GroupLabel(kinds[kind] ?: "Other")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Assignments, plateOffset = 3.dp) {
                list.sortedBy { it.value.name }.forEachIndexed { i, (key, file) ->
                    if (i > 0) Rule()
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { repo.files.open(file) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(file.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            InkText(sizeText(file.length()), style = RisoType.small)
                        }
                        Box(Modifier.clickable(role = Role.Button) { repo.files.delete(key); haptics.tick() }.padding(4.dp)) { InkChip("Delete") }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.clickable(role = Role.Button) {
            if (sure) {
                repo.files.clear()
                haptics.confirm()
            }
            sure = !sure
        }) { InkChip(if (sure) "Tap again to delete all ${saved.size}" else "Delete all downloads", solid = sure) }
    }
}
