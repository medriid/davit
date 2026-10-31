package dev.medriid.davit.ui

import dev.medriid.davit.design.EditorialShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import dev.medriid.davit.data.key
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Reminder
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.remainingFor
import dev.medriid.davit.data.remarkFor
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.roundToInt

// The Editorial look's own layouts: Swiss modernism on a phone. A masthead, a strict
// two-column grid of tiles that are alive (they count up, turn over, tilt under your finger)
// and an index for attendance. Only shown when the Editorial colours are picked.

private val Gap = 8.dp

/**
 * The display card in the Editorial look: your print or photo, wide, with the overall
 * attendance set on it like a caption. [onChange] adds the button that opens its settings.
 */
@Composable
internal fun EditorialPicture(snapshot: Snapshot?, onClick: () -> Unit, onChange: (() -> Unit)?) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val inks = LocalInks.current
    val att = snapshot?.attendance.orEmpty()
    val total = att.sumOf { it.total }
    val overall = if (total == 0) null else att.sumOf { it.attended } * 100 / total
    val risky = if (snapshot?.attendanceExempt == true) 0 else att.count { it.atRisk }
    Tile(
        Modifier.fillMaxWidth().height(168.dp),
        description = "Attendance ${overall ?: "unknown"} percent overall. Opens attendance.",
        fill = inks.surface,
        onClick = onClick,
    ) {
        BannerBackground(state.banner, state.bannerPhotoAt, repo.bannerPhoto, Modifier.matchParentSize())
        // The figure on a paper block, bottom left, as a caption sits on a photograph.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .background(inks.paper)
                .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        ) {
            InkText("Attendance", label = true)
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp(overall ?: 0, "%", RisoType.display(48.sp).copy(color = if (overall != null && overall < 75) inks.a else inks.text))
                Spacer(Modifier.width(10.dp))
                InkText(if (risky == 0) "all clear" else "$risky at risk", style = RisoType.small.copy(color = if (risky > 0) inks.a else inks.muted, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        if (onChange != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                SquareButton(RisoIcon.Settings, "Change the picture", size = 36.dp, onPaper = true, onClick = onChange)
            }
        }
    }
}

/** Home in the Editorial look: a masthead, then the day as a grid of live tiles. */
@Composable
internal fun EditorialHome(snapshot: Snapshot, reminders: List<Reminder>, onOpen: (Route) -> Unit, onOpenAttendance: () -> Unit, onOpenWeek: () -> Unit, onOpenAcademics: () -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val inks = LocalInks.current
    val nav = LocalNav.current
    val now = remember { LocalDateTime.now() }
    val today = now.toLocalDate()
    val plan = remember(snapshot) { planFor(weekOf(snapshot), now, snapshot.calendar) }
    val due = snapshot.assignments.flatMap { c -> c.items.map { c to it } }
        .filter { (_, a) -> a.updated == null }
        .mapNotNull { (c, a) -> vtopDate(a.due)?.takeIf { !it.isBefore(today) && ChronoUnit.DAYS.between(today, it) <= 7 }?.let { Triple(c, a, it) } }
        .sortedBy { it.third }
    val exam = snapshot.exams.mapNotNull { e -> vtopDate(e.date)?.takeIf { !it.isBefore(today) }?.let { e to it } }.minByOrNull { it.second }

    TabColumn {
        // Masthead: the date and the week like a paper's dateline, More and you at the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(now.format(DateTimeFormatter.ofPattern("EEEE · d MMMM", Locale.ENGLISH)), label = true)
                InkText("Week ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${snapshot.semester.name.substringBefore(" - ")}", style = RisoType.small.copy(color = inks.muted))
            }
            SquareButton(RisoIcon.More, "More from VTOP") { onOpen(Route.Campus) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clickable(role = Role.Button) { onOpen(Route.Settings) }.semantics { contentDescription = "Profile and settings" }) {
                MascotAvatar(Mascot.parse(state.mascot), 44.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        val first = snapshot.profile?.name?.substringBefore(' ')?.lowercase()?.replaceFirstChar { it.uppercase() }?.ifBlank { null }
        Reveal(0) {
            BasicText(
                greeting(now.hour) + (first?.let { ",\n$it." } ?: "."),
                style = RisoType.display(44.sp).copy(color = inks.text),
            )
        }
        Spacer(Modifier.height(14.dp))
        Rule()
        Spacer(Modifier.height(Gap * 2))

        // 1. Your picture, wide: the display card, with the overall figure set on it.
        Reveal(1) { EditorialPicture(snapshot, onClick = onOpenAttendance, onChange = { onOpen(Route.DisplayCard) }) }
        Spacer(Modifier.height(Gap))

        // 2. Now or next, and the courses turning over.
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            val slot = plan.now ?: plan.next
            Reveal(2, Modifier.weight(1f)) {
                Tile(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    description = slot?.let { "${if (plan.now != null) "On now" else "Next"}: ${it.course.courseName} at ${clock(it.meeting.start)}" } ?: "No classes coming up",
                    fill = if (plan.now != null) inks.text else inks.surface,
                    onClick = onOpenWeek,
                ) {
                    val ink = if (plan.now != null) inks.paper else inks.text
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        BasicText(
                            (if (plan.now != null) "On now" else when (plan.nextInDays) { 0 -> "Next"; 1 -> "Tomorrow"; else -> "In ${plan.nextInDays} days" }).uppercase(),
                            style = RisoType.label.copy(color = if (plan.now != null) inks.a else inks.muted),
                        )
                        Spacer(Modifier.weight(1f))
                        BasicText(slot?.let { clock(it.meeting.start) } ?: "—", style = RisoType.display(40.sp).copy(color = ink))
                        Spacer(Modifier.height(4.dp))
                        BasicText(slot?.course?.courseName ?: "Nothing scheduled", style = RisoType.title.copy(color = ink, fontSize = 15.sp), maxLines = 2)
                        slot?.course?.venue?.ifBlank { null }?.let { BasicText(it, style = RisoType.small.copy(color = ink.copy(alpha = 0.7f), fontSize = 13.sp)) }
                    }
                }
            }
            Reveal(3, Modifier.weight(1f)) { MarksTicker(snapshot) }
        }
        Spacer(Modifier.height(Gap))

        // 3. What's due, and the next exam.
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            Reveal(4, Modifier.weight(1f)) {
                Tile(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    description = "${due.size} due this week. Opens academics.",
                    onClick = {
                        due.firstOrNull()?.let { (c, a, _) -> nav.go(Route.Assignment(c.classId, a.code.ifEmpty { "#0" })) } ?: onOpenAcademics()
                    },
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        InkText("Due this week", label = true)
                        Spacer(Modifier.weight(1f))
                        CountUp(due.size, "", RisoType.display(54.sp).copy(color = if (due.isNotEmpty()) inks.text else inks.muted))
                        InkText(due.firstOrNull()?.let { (_, a, d) -> "${a.title} · ${dayWord(ChronoUnit.DAYS.between(today, d))}" } ?: "Nothing due", style = RisoType.small.copy(fontSize = 13.sp), maxLines = 2)
                    }
                }
            }
            Reveal(5, Modifier.weight(1f)) {
                Tile(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    description = exam?.let { "${it.first.type} in ${ChronoUnit.DAYS.between(today, it.second)} days" } ?: "No exams coming up",
                    fill = inks.raised,
                    onClick = { onOpen(Route.ExamBoard) },
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        InkText(exam?.first?.type ?: "Exams", label = true)
                        Spacer(Modifier.weight(1f))
                        if (exam != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                CountUp(ChronoUnit.DAYS.between(today, exam.second).toInt(), "", RisoType.display(54.sp).copy(color = inks.text))
                                Spacer(Modifier.width(6.dp))
                                InkText("days", style = RisoType.small.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 10.dp))
                            }
                            InkText("${exam.first.courseName} · ${exam.second.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}", style = RisoType.small.copy(fontSize = 13.sp), maxLines = 2)
                        } else {
                            BasicText("—", style = RisoType.display(54.sp).copy(color = inks.muted))
                            InkText("None scheduled", style = RisoType.small.copy(fontSize = 13.sp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Gap))

        // 4. The one red block: plan a leave.
        Reveal(6) {
            Tile(Modifier.fillMaxWidth().height(96.dp), description = "Plan a leave", fill = inks.a, onClick = { onOpen(Route.LeavePlanner) }) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Plan a leave", style = RisoType.display(28.sp).copy(color = Color.White))
                        BasicText("What a day away would cost each course", style = RisoType.small.copy(color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp))
                    }
                    BasicText("→", style = RisoType.display(34.sp).copy(color = Color.White))
                }
            }
        }
        Spacer(Modifier.height(Gap))

        // 5. Everything else, sorted into numbered sections like a paper's index.
        Spacer(Modifier.height(Gap * 3))
        Hub(snapshot, onOpen, onOpenWeek)
        Spacer(Modifier.height(8.dp))
    }
}

/** One way in from the hub: a label, an icon when one fits, and where it goes. */
private class HubItem(val label: String, val icon: RisoIcon?, val go: () -> Unit)

/**
 * The index: every part of daVIT in five numbered sections, each a row of small tiles. Study
 * opens Academics at the right section; the rest open their own screens.
 */
@Composable
private fun Hub(snapshot: Snapshot, onOpen: (Route) -> Unit, onOpenWeek: () -> Unit) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val inks = LocalInks.current
    fun academics(section: Int): () -> Unit = {
        repo.academicsFocus.value = section
        nav.tab(2)
    }
    val sections = listOf(
        "Study" to listOf(
            HubItem("Marks", RisoIcon.Marks, academics(0)),
            HubItem("Assignments", RisoIcon.Assignments, academics(1)),
            HubItem("Exam board", RisoIcon.Exams) { onOpen(Route.ExamBoard) },
            HubItem("Grades & CGPA", RisoIcon.Grades, academics(3)),
            HubItem("Materials", RisoIcon.Materials, academics(4)),
            HubItem("Faculty", RisoIcon.Faculty, academics(5)),
        ),
        "Time" to listOf(
            HubItem("Week", RisoIcon.Week, onOpenWeek),
            HubItem("Curriculum", RisoIcon.Book) { onOpen(Route.Curriculum) },
            HubItem("Plan next sem", RisoIcon.Today) { onOpen(Route.Planner) },
            HubItem("Free with friends", RisoIcon.People) { onOpen(Route.Friends) },
            HubItem("Allocation", RisoIcon.Next) { onOpen(Route.Allocation) },
        ),
        "Campus" to listOfNotNull(
            HubItem("Hostel", RisoIcon.Home) { onOpen(Route.Hostel) }.takeIf { snapshot.profile?.hostel != null },
            HubItem("Map", RisoIcon.Map) { nav.tab(4) },
            HubItem("Club events", RisoIcon.Flag) { onOpen(Route.Events) },
            HubItem("Messages", RisoIcon.Bubble) { onOpen(Route.Messages) },
            HubItem("Spot-light", RisoIcon.Megaphone) { onOpen(Route.Spotlight) },
            HubItem("Proctor & HOD", RisoIcon.People) { onOpen(Route.People) },
        ),
        "Requests & records" to listOf(
            HubItem("Bonafide", RisoIcon.Certificate) { onOpen(Route.Bonafide) },
            HubItem("Course feedback", RisoIcon.Pencil) { onOpen(Route.CourseFeedback) },
            HubItem("Paper seeing", RisoIcon.Eye) { onOpen(Route.PaperSeeing) },
            HubItem("Receipts", RisoIcon.Receipt) { onOpen(Route.Money) },
            HubItem("Gate log", RisoIcon.Gate) { onOpen(Route.Gate) },
            HubItem("Activities", RisoIcon.Trophy) { onOpen(Route.Activities) },
            HubItem("Corrections", RisoIcon.Attendance) { onOpen(Route.Corrections) },
        ),
        "Yours" to listOf(
            HubItem("Course names", RisoIcon.Tag) { onOpen(Route.CourseNames) },
            HubItem("Downloads", RisoIcon.Download) { onOpen(Route.Downloads) },
            HubItem("Display card", RisoIcon.Picture) { onOpen(Route.DisplayCard) },
            HubItem("Settings", RisoIcon.Settings) { onOpen(Route.Settings) },
        ),
    )
    var n = 8
    sections.forEachIndexed { s, (title, items) ->
        Reveal(n++) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
                BasicText("%02d".format(s + 1), Modifier.width(34.dp), style = RisoType.label.copy(color = inks.a))
                BasicText(title, style = RisoType.display(22.sp).copy(color = inks.text))
            }
        }
        items.chunked(3).forEach { row ->
            Reveal(n++) {
                Row(Modifier.padding(bottom = Gap), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    row.forEach { item ->
                        Tile(Modifier.weight(1f).height(78.dp), description = item.label, onClick = item.go) {
                            Column(Modifier.fillMaxSize().padding(10.dp)) {
                                if (item.icon != null) IconGlyph(item.icon, inks.text, Modifier.size(20.dp))
                                else BasicText("↗", style = RisoType.label.copy(color = inks.muted))
                                Spacer(Modifier.weight(1f))
                                BasicText(item.label, style = RisoType.title.copy(color = inks.text, fontSize = 14.sp, lineHeight = 16.sp), maxLines = 2)
                            }
                        }
                    }
                    // Keep the grid: empty cells hold their place.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(Gap * 2))
    }
}

/**
 * Course names in the Editorial look: a numbered list, VTOP's name above an underlined field
 * for yours. The number turns red once a course has your name.
 */
@Composable
internal fun EditorialNameRows(courses: List<Pair<String, String>>, aliases: Map<String, String>, onName: (String, String) -> Unit) {
    courses.forEachIndexed { i, (code, vtop) ->
        Reveal(i) { NameRow(i + 1, code, vtop, aliases[code].orEmpty(), onName) }
    }
}

@Composable
private fun NameRow(n: Int, code: String, vtop: String, saved: String, onName: (String, String) -> Unit) {
    val inks = LocalInks.current
    var name by remember(code) { mutableStateOf(saved) }
    var focused by remember { mutableStateOf(false) }
    val line by animateDpAsState(if (focused) 2.dp else 1.dp, label = "line")
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        BasicText(
            "%02d".format(n),
            Modifier.width(34.dp).padding(top = 4.dp),
            style = RisoType.label.copy(color = if (name.isNotBlank()) inks.a else inks.muted),
        )
        Column(Modifier.weight(1f)) {
            BasicText(vtop, style = RisoType.title.copy(color = inks.text, fontSize = 17.sp), maxLines = 2)
            BasicText(code, style = RisoType.small.copy(color = inks.muted, fontSize = 13.sp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(40)
                        onName(code, name)
                    },
                    singleLine = true,
                    textStyle = RisoType.body.copy(color = inks.text, fontSize = 18.sp),
                    cursorBrush = SolidColor(inks.a),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Your name for $vtop" },
                    decorationBox = { field ->
                        Box {
                            if (name.isEmpty()) BasicText("Your name for it", style = RisoType.body.copy(color = inks.muted, fontSize = 18.sp))
                            field()
                        }
                    },
                )
                if (name.isNotEmpty()) {
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                            .clickable(role = Role.Button) {
                                name = ""
                                onName(code, "")
                            },
                        contentAlignment = Alignment.CenterEnd,
                    ) { BasicText("CLEAR", style = RisoType.label.copy(color = inks.muted)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(line).background(if (focused) inks.a else inks.text))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(inks.rule))
}

/** Attendance in the Editorial look: a numbered index, worst first; tap a line to open it. */
@Composable
internal fun EditorialAttendance(snapshot: Snapshot) {
    val inks = LocalInks.current
    val nav = LocalNav.current
    val now = remember { LocalDateTime.now() }
    val rows = snapshot.attendance.sortedBy { it.percent }
    var open by rememberSaveable { mutableStateOf<String?>(rows.firstOrNull { it.atRisk }?.key) }
    TabColumn {
        Reveal(0) { BasicText("Attendance", style = RisoType.display(44.sp).copy(color = inks.text)) }
        Spacer(Modifier.height(6.dp))
        InkText(
            when {
                snapshot.attendanceExempt -> "CGPA 9 or above: the 75% rule doesn't apply to you."
                else -> "${rows.count { it.atRisk }} under 75% · ${rows.count { !it.atRisk }} safe · worst first"
            },
            style = RisoType.small.copy(color = inks.muted),
        )
        Spacer(Modifier.height(14.dp))
        Rule()
        if (rows.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            InkText("VTOP has no attendance for ${snapshot.semester.name} yet.")
            return@TabColumn
        }
        rows.forEachIndexed { i, row ->
            val isOpen = open == row.key
            val risky = row.atRisk && !snapshot.attendanceExempt
            Reveal(i + 1) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { open = if (isOpen) null else row.key }
                        .semantics { contentDescription = "${row.courseName}, ${row.percent} percent. ${if (isOpen) "Close" else "Open"}." }
                        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f))
                        .padding(vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("%02d".format(i + 1), Modifier.width(34.dp), style = RisoType.label.copy(color = inks.muted))
                        Column(Modifier.weight(1f)) {
                            InkText(row.courseName, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 2)
                            InkText((if (isLabType(row.classType)) "Lab" else "Theory") + " · ${row.attended}/${row.total}", style = RisoType.small.copy(color = inks.muted, fontSize = 13.sp))
                        }
                        BasicText("${row.percent}%", style = RisoType.display(34.sp).copy(color = if (risky) inks.a else inks.text))
                    }
                    if (isOpen) {
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.padding(start = 34.dp)) {
                            HalftoneBar(row.attended / row.total.coerceAtLeast(1).toFloat(), threshold = 0.75f, atRisk = risky)
                            Spacer(Modifier.height(10.dp))
                            InkText(row.hint(snapshot.remainingFor(row, now)).replaceFirstChar { it.uppercase() } + ".", style = RisoType.body.copy(fontWeight = FontWeight.Bold))
                            snapshot.feeds.remarkFor(row)?.let {
                                Spacer(Modifier.height(6.dp))
                                InkText("VTOP: ${it.remark}", style = RisoType.small.copy(color = if (it.serious) inks.a else inks.muted, fontWeight = FontWeight.Bold))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                InkText("Plan a leave  →", style = RisoType.label.copy(color = inks.a), modifier = Modifier.clickable(role = Role.Button) { nav.go(Route.LeavePlanner) }.padding(vertical = 6.dp))
                                InkText("Marked wrong?  →", style = RisoType.label.copy(color = inks.a), modifier = Modifier.clickable(role = Role.Button) { nav.go(Route.Corrections) }.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(inks.rule))
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------

/**
 * A tile: flat, near-square, on the grid. It tilts towards your finger as you press, the way
 * a card would on a table, and sinks a little; then it springs back.
 */
@Composable
private fun Tile(
    modifier: Modifier,
    description: String,
    fill: Color = LocalInks.current.surface,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val inks = LocalInks.current
    val haptics = rememberHaptics()
    val motion = rememberMotionEnabled()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val sink = remember { Animatable(1f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .graphicsLayer {
                rotationX = tiltX.value
                rotationY = tiltY.value
                scaleX = sink.value
                scaleY = sink.value
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(3.dp))
            .background(fill)
            .border(1.dp, if (fill == inks.surface || fill == inks.raised) inks.rule else Color.Transparent, RoundedCornerShape(3.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(onClick, motion) {
                size = this.size
                detectTapGestures(
                    onPress = { at ->
                        if (motion) scope.launch {
                            // Lean the side you pressed away from you.
                            val dx = (at.x / this@pointerInput.size.width - 0.5f).coerceIn(-0.5f, 0.5f)
                            val dy = (at.y / this@pointerInput.size.height - 0.5f).coerceIn(-0.5f, 0.5f)
                            launch { tiltY.animateTo(dx * 14f, tween(110)) }
                            launch { tiltX.animateTo(-dy * 14f, tween(110)) }
                            launch { sink.animateTo(0.965f, tween(110)) }
                        }
                        tryAwaitRelease()
                        scope.launch {
                            val back = spring<Float>(dampingRatio = 0.5f, stiffness = 380f)
                            launch { tiltX.animateTo(0f, back) }
                            launch { tiltY.animateTo(0f, back) }
                            launch { sink.animateTo(1f, back) }
                        }
                    },
                    onTap = {
                        haptics.tick()
                        onClick()
                    },
                )
            },
        content = content,
    )
}

/**
 * Your marks, one at a time, turning over every few seconds like a departure board: the
 * score big, then the course and what it was for. Tap to open Marks.
 */
@Composable
private fun MarksTicker(snapshot: Snapshot) {
    val inks = LocalInks.current
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val motion = rememberMotionEnabled()
    val marks = remember(snapshot) {
        snapshot.marks.flatMap { c -> c.items.filter { it.scored != null && it.max != null }.map { c to it } }
    }
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(marks, motion) {
        if (!motion || marks.size < 2) return@LaunchedEffect
        while (true) {
            delay(3200)
            i = (i + 1) % marks.size
        }
    }
    Tile(
        Modifier.fillMaxWidth().aspectRatio(1f),
        description = if (marks.isEmpty()) "No marks yet. Opens marks." else "Marks: " + marks.joinToString { (c, m) -> "${c.courseName} ${m.title} ${num(m.scored)} of ${num(m.max)}" },
        onClick = {
            repo.academicsFocus.value = 0
            nav.tab(2)
        },
    ) {
        AnimatedContent(
            targetState = i,
            transitionSpec = {
                (slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(300))) togetherWith
                    (slideOutVertically(tween(320)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "ticker",
        ) { k ->
            val pair = marks.getOrNull(k)
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                BasicText(if (marks.size > 1) "MARKS · ${k + 1} OF ${marks.size}" else "MARKS", style = RisoType.label.copy(color = inks.muted))
                Spacer(Modifier.weight(1f))
                if (pair == null) {
                    BasicText("—", style = RisoType.display(44.sp).copy(color = inks.muted))
                    BasicText("None posted yet", style = RisoType.title.copy(color = inks.text, fontSize = 15.sp))
                } else {
                    val (c, m) = pair
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(num(m.scored), style = RisoType.display(44.sp).copy(color = inks.text))
                        BasicText("/${num(m.max)}", style = RisoType.display(22.sp).copy(color = inks.muted), modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(c.courseName, style = RisoType.title.copy(color = inks.text, fontSize = 15.sp), maxLines = 1)
                    BasicText(m.title, style = RisoType.small.copy(color = inks.muted, fontSize = 13.sp), maxLines = 1)
                }
            }
        }
    }
}

/** 9.0 → "9", 4.5 → "4.5". */
private fun num(v: Double?): String = when {
    v == null -> "—"
    v == Math.floor(v) -> v.toInt().toString()
    else -> "%.1f".format(Locale.ENGLISH, v)
}

/** A number that counts up to [value] the first time it shows. */
@Composable
private fun CountUp(value: Int, suffix: String, style: androidx.compose.ui.text.TextStyle) {
    val motion = rememberMotionEnabled()
    var target by remember { mutableIntStateOf(if (motion) 0 else value) }
    LaunchedEffect(value) { target = value }
    val shown by animateFloatAsState(target.toFloat(), tween(if (motion) 900 else 0, easing = FastOutSlowInEasing), label = "count")
    BasicText("${shown.roundToInt()}$suffix", style = style)
}

/** Slides up and fades in, a beat after the one before it, the first time the screen shows. */
@Composable
private fun Reveal(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val shown = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        delay(60L * index)
        shown.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f))
    }
    Box(modifier.graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 40f * density }) { content() }
}

@Composable
private fun SquareButton(icon: RisoIcon, label: String, size: Dp = 44.dp, onPaper: Boolean = false, onClick: () -> Unit) {
    val inks = LocalInks.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(3.dp))
            .background(if (onPaper) inks.paper else Color.Transparent)
            .border(1.dp, inks.rule, RoundedCornerShape(3.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon, inks.text, Modifier.size(size * 0.5f)) }
}

private fun greeting(hour: Int) = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun dayWord(days: Long) = when (days) {
    0L -> "today"
    1L -> "tomorrow"
    else -> "in $days days"
}
