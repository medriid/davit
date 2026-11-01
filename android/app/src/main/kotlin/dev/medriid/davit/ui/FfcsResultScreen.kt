package dev.medriid.davit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Section
import dev.medriid.davit.data.asPicks
import dev.medriid.davit.data.clashes
import dev.medriid.davit.data.courseName
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.vtop.SlotTimes
import dev.medriid.davit.vtop.TimetableCourse
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Your FFCS result: next semester's registered classes as a timetable and as a table of courses. Read only. */
@Composable
internal fun FfcsResultScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    val busy = Section.Registered in loading
    var view by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Registered) }

    val result = campus.registered
    RouteScreen(
        route, "FFCS result", result?.semester?.name ?: "What you registered for next semester.",
        refreshing = busy, onRefresh = { repo.campus.refresh(Section.Registered, force = true) },
    ) {
        errors[Section.Registered]?.let { ErrorNote(it) { repo.campus.refresh(Section.Registered, force = true) } }
        val courses = result?.courses.orEmpty().map { it.copy(courseName = state.aliases.courseName(it.courseCode, it.courseName)) }
        if (courses.isEmpty()) {
            when {
                busy -> Loading("Reading your registration")
                errors[Section.Registered] == null -> InkText("No registered classes for next semester yet. They show here once you've registered on VTOP.")
            }
            return@RouteScreen
        }

        val clashes = courses.asPicks().clashes()
        val shades = courseShades(courses)
        val codes = courses.groupBy { it.courseCode }
        val short = shortNames(codes)

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Stat("${codes.size}", if (codes.size == 1) "course" else "courses", Modifier.weight(1f))
            Stat(creditsText(courses.sumOf { it.credits }), "credits", Modifier.weight(1f))
            Stat("${clashes.size}", if (clashes.size == 1) "clash" else "clashes", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ChoiceRow(listOf("Timetable", "Courses"), view) { view = it } }
            InkChip(if (courses.all { it.approved }) "Approved" else "Not approved yet")
        }
        Spacer(Modifier.height(12.dp))
        if (clashes.isNotEmpty()) ClashCard(clashes)

        if (view == 0) {
            TimetableGrid(courses, shades, short, clashes.flatMap { listOf(it.a.slot, it.b.slot) }.toSet())
            GroupLabel("Key")
            CourseKey(codes, shades, short)
        } else {
            CourseTable(codes, shades, courses.sumOf { it.credits })
        }
    }
}

@Composable
private fun Stat(number: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Heading(number, 30.sp)
        InkText(label, style = RisoType.small)
    }
}

/** A colour for each course, evenly round the wheel so they stay apart, soft enough to read [Inks.text] on. */
@Composable
private fun courseShades(courses: List<TimetableCourse>): Map<String, Color> {
    val dark = LocalInks.current.dark
    val codes = courses.map { it.courseCode }.distinct()
    val steps = maxOf(codes.size, 6)
    return codes.withIndex().associate { (i, code) ->
        val hue = 360f * i / steps
        code to if (dark) Color.hsl(hue, 0.38f, 0.30f) else Color.hsl(hue, 0.50f, 0.82f)
    }
}

/** The week as a grid: a column a day, a row an hour, each class a block at its real time. */
@Composable
private fun TimetableGrid(courses: List<TimetableCourse>, shades: Map<String, Color>, short: Map<String, String>, clashing: Set<String>) {
    val inks = LocalInks.current
    val meetings = courses.flatMap { c -> SlotTimes.meetings(c.slot).map { it to c } }
    if (meetings.isEmpty()) {
        InkText("None of these classes has a slot in the week.")
        return
    }
    val startH = meetings.minOf { it.first.start } / 60
    val endH = (meetings.maxOf { it.first.end } + 59) / 60
    val days = ((1..5) + meetings.map { it.first.day }).distinct().sorted()
    val hour = 52.dp
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(26.dp)) {
            Spacer(Modifier.height(24.dp))
            for (h in startH until endH) {
                Box(Modifier.height(hour)) { BasicText("%02d".format(h), style = RisoType.small.copy(color = inks.text, fontSize = 11.sp)) }
            }
        }
        days.forEach { day ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    InkText(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.ENGLISH), label = true)
                }
                Box(Modifier.fillMaxWidth().height(hour * (endH - startH))) {
                    Canvas(Modifier.matchParentSize()) {
                        val hp = hour.toPx()
                        val line = 1.dp.toPx()
                        for (i in 0..(endH - startH)) drawLine(inks.rule, Offset(0f, i * hp), Offset(size.width, i * hp), line)
                        drawLine(inks.rule, Offset(0f, 0f), Offset(0f, size.height), line)
                        if (day == days.last()) drawLine(inks.rule, Offset(size.width, 0f), Offset(size.width, size.height), line)
                    }
                    meetings.filter { it.first.day == day }.forEach { (m, c) ->
                        val shape = RoundedCornerShape(4.dp)
                        val long = m.end - m.start > 60
                        Box(
                            Modifier
                                .padding(top = hour * ((m.start - startH * 60) / 60f))
                                .padding(horizontal = 1.5.dp)
                                .fillMaxWidth()
                                .height(hour * ((m.end - m.start) / 60f))
                                .clip(shape)
                                .background(shades[c.courseCode] ?: inks.b)
                                .then(if (c.slot in clashing) Modifier.border(2.dp, inks.a, shape) else Modifier)
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                        ) {
                            Column {
                                BasicText(short[c.courseCode].orEmpty(), style = RisoType.small.copy(color = inks.text, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                                BasicText(c.venue, style = RisoType.small.copy(color = inks.text, fontSize = 10.sp), maxLines = 1)
                                if (long) BasicText(kindOf(c), style = RisoType.small.copy(color = inks.text, fontSize = 10.sp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the grid's short names and colours stand for. */
@Composable
private fun CourseKey(codes: Map<String, List<TimetableCourse>>, shades: Map<String, Color>, short: Map<String, String>) {
    codes.forEach { (code, classes) ->
        val first = classes.first()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(shades[code] ?: LocalInks.current.b))
            Spacer(Modifier.width(8.dp))
            InkText(short[code].orEmpty(), style = RisoType.small.copy(fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.width(56.dp))
            InkText(
                first.courseName + if (classes.all { it.slot.isBlank() }) " · no slot" else "",
                style = RisoType.small,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TypeWidth = 66.dp
private val RoomWidth = 68.dp
private val CreditWidth = 26.dp
private val BarSpace = 15.dp

/** Every class in a table, grouped by course: type, slot, room and credits line up down the page. */
@Composable
private fun CourseTable(codes: Map<String, List<TimetableCourse>>, shades: Map<String, Color>, credits: Double) {
    val inks = LocalInks.current
    TableRow("Type", "Slot", "Room", "Cr", Modifier.padding(start = BarSpace, bottom = 6.dp), head = true)
    Hairline()
    codes.forEach { (code, classes) ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 10.dp)) {
            Box(Modifier.width(5.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(shades[code] ?: inks.b))
            Spacer(Modifier.width(BarSpace - 5.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                InkText(classes.first().courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(
                    (listOf(code) + classes.map { titleCase(it.faculty) }.filter { it.isNotBlank() }.distinct()).joinToString(" · "),
                    style = RisoType.small,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                classes.forEach { c ->
                    TableRow(kindOf(c), c.slot.ifBlank { "No slot" }, c.venue.ifBlank { "–" }, creditsText(c.credits))
                }
            }
        }
        Hairline()
    }
    Row(Modifier.fillMaxWidth().padding(start = BarSpace, top = 10.dp)) {
        InkText("Total credits", style = RisoType.body.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        InkText(creditsText(credits), style = RisoType.body.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.End))
    }
}

@Composable
private fun TableRow(type: String, slot: String, room: String, credits: String, modifier: Modifier = Modifier, head: Boolean = false) {
    @Composable
    fun Cell(text: String, cell: Modifier, end: Boolean = false) {
        val align = if (end) TextAlign.End else TextAlign.Start
        if (head) InkText(text, label = true, modifier = cell)
        else InkText(text, style = RisoType.small.copy(textAlign = align), maxLines = 2, modifier = cell)
    }
    Row(modifier.fillMaxWidth()) {
        Cell(type, Modifier.width(TypeWidth))
        Cell(slot, Modifier.weight(1f).padding(end = 6.dp))
        Cell(room, Modifier.width(RoomWidth))
        Cell(credits, Modifier.width(CreditWidth), end = true)
    }
}

/**
 * Each course's name small enough for a grid cell: your own short name, or its initials. Two that
 * would read the same keep more of their last word: Data Structures and Data Science, "DStr" and "DSci".
 */
private fun shortNames(codes: Map<String, List<TimetableCourse>>): Map<String, String> {
    val names = codes.mapValues { (_, classes) -> classes.first().courseName }
    val short = names.mapValues { (_, name) -> if (name.length <= 6) name else courseInitials(name) }
    return short.mapValues { (code, s) ->
        if (short.values.count { it == s } == 1) return@mapValues s
        val words = names.getValue(code).split(' ').filter { it.isNotBlank() }
        words.dropLast(1).joinToString("") { it.first().uppercase() } + words.last().take(3)
    }
}

/** "Embedded Theory" → "Theory", "Lab Only" → "Lab", "Online Course" → "Online". */
private fun kindOf(c: TimetableCourse): String = when {
    c.courseType.contains("lab", ignoreCase = true) -> "Lab"
    c.courseType.contains("theory", ignoreCase = true) -> "Theory"
    c.courseType.contains("online", ignoreCase = true) -> "Online"
    else -> c.courseType
}

private fun creditsText(credits: Double) = if (credits % 1.0 == 0.0) credits.toInt().toString() else credits.toString()
