package dev.medriid.davit.ui

import dev.medriid.davit.data.bannerText
import dev.medriid.davit.data.remarkFor
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Reminder
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.key
import dev.medriid.davit.data.overallPercent
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.remainingFor
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// The Soft look's own layouts: a calm app home rather than a printed sheet. Only shown
// when the Soft colours are picked; every other look keeps its own screens.

/** Home in the Soft look: hello, attendance at a glance with its trend, the next class, the main action, what's due. */
@Composable
internal fun SoftHome(snapshot: Snapshot, reminders: List<Reminder>, onOpen: (Route) -> Unit, onOpenWeek: () -> Unit, onOpenAttendance: () -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val inks = LocalInks.current
    val now = remember { LocalDateTime.now() }
    val plan = remember(snapshot) { planFor(weekOf(snapshot), now, snapshot.calendar) }
    val att = snapshot.attendance
    val overall = overallPercent(att)

    TabColumn {
        // Hello, with your mascot, More and Settings.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(role = Role.Button) { onOpen(Route.Settings) }.semantics { contentDescription = "Profile and settings" }) {
                MascotAvatar(Mascot.parse(state.mascot), 48.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                InkText("Welcome back,", style = RisoType.small)
                BasicText(
                    snapshot.profile?.name?.substringBefore(' ')?.lowercase()?.replaceFirstChar { it.uppercase() }?.ifBlank { null } ?: "there",
                    style = RisoType.display(24.sp).copy(color = inks.text, fontWeight = FontWeight.Normal), maxLines = 1,
                )
            }
            CircleButton(RisoIcon.More, "More from VTOP") { onOpen(Route.Campus) }
            Spacer(Modifier.width(8.dp))
            CircleButton(RisoIcon.Settings, "Settings") { onOpen(Route.Settings) }
        }
        Spacer(Modifier.height(18.dp))

        // Your display card: your print or photo on top with its two stats, attendance under it,
        // and every course as a little pastel token. Tap the picture to change it.
        Column(Modifier.fillMaxWidth().clip(SoftCardShape).background(inks.surface)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable(role = Role.Button) { onOpen(Route.DisplayCard) }
                    .semantics { contentDescription = "Change your display card" },
            ) {
                BannerBackground(state.banner, state.bannerPhotoAt, repo.bannerPhoto, Modifier.matchParentSize())
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    // The card already says the overall figure big; a chip that repeats it is left off.
                    fun chip(id: String) = dev.medriid.davit.data.BannerStat.of(id).takeIf { it != dev.medriid.davit.data.BannerStat.Attendance }?.let { snapshot.bannerText(it, reminders, now) }
                    chip(state.banner.left)?.let { StatChip(it) } ?: Spacer(Modifier)
                    chip(state.banner.right)?.let { StatChip(it) }
                }
                // The big number, on a paper tab at the bottom left, as if clipped onto the picture.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp)
                        .offset(y = 22.dp)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(inks.surface)
                        .padding(start = 16.dp, end = 18.dp, top = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicText(overall?.let { "$it%" } ?: "–", style = RisoType.display(46.sp).copy(color = inks.text))
                    Spacer(Modifier.width(6.dp))
                    Sparkle(inks.a, Modifier.size(22.dp).offset(y = (-18).dp))
                }
            }
            Column(Modifier.padding(start = 30.dp, end = 18.dp, top = 26.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkText(
                        "overall · " + if (snapshot.attendanceExempt) "75% rule doesn't apply" else "${att.count { it.atRisk }} at risk",
                        style = RisoType.small.copy(color = inks.text.copy(alpha = 0.65f)),
                        modifier = Modifier.weight(1f),
                    )
                    CircleButton(RisoIcon.Week, "Your week", size = 38.dp, onClick = onOpenWeek)
                }
                Spacer(Modifier.height(12.dp))
                // Every course as a token: its initials and percent, coral when it's under the line.
                FlowRowCompat(spacing = 8.dp) {
                    att.sortedBy { it.percent }.forEach { row ->
                        val risky = row.atRisk && !snapshot.attendanceExempt
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (risky) inks.a else Kind.entries[(row.courseCode.hashCode() and 0x7fffffff) % Kind.entries.size].pastel)
                                .clickable(role = Role.Button, onClick = onOpenAttendance)
                                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                                BasicText(initials(row.courseName), style = RisoType.small.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A)))
                            }
                            Spacer(Modifier.width(6.dp))
                            BasicText(
                                "${row.percent}%" + if (isLabType(row.classType)) " lab" else "",
                                style = RisoType.small.copy(fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A)),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // The class on now or next, in butter, with today's classes as little bubbles.
        val slot = plan.now ?: plan.next
        InkCard(Modifier.fillMaxWidth(), kind = Kind.Assignments, contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
            InkText(if (plan.now != null) "On now" else "Next class", label = true)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(slot?.let { clock(it.meeting.start) } ?: "–", style = RisoType.display(40.sp).copy(color = inks.text))
                Spacer(Modifier.width(6.dp))
                Sparkle(inks.a, Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        plan.today.take(5).forEachIndexed { i, s -> Bubble(initials(s.course.courseName), Kind.entries[(i * 3 + 1) % Kind.entries.size].pastel) }
                    }
                    Spacer(Modifier.height(4.dp))
                    InkText(if (plan.today.isEmpty()) "Nothing today" else "${plan.today.size} today", style = RisoType.small)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, inks.text.copy(alpha = 0.4f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    IconGlyph(RisoIcon.Map, inks.text, Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                InkText(
                    slot?.let { s ->
                        listOfNotNull(
                            s.course.courseName,
                            s.course.venue.ifBlank { null },
                            when (plan.nextInDays) { 0 -> null; 1 -> "tomorrow"; else -> "in ${plan.nextInDays} days" }.takeIf { plan.now == null },
                        ).joinToString(" · ")
                    } ?: "No classes coming up.",
                    style = RisoType.small, maxLines = 2,
                )
            }
            slot?.attendance?.let { row ->
                Spacer(Modifier.height(4.dp))
                InkText("${row.percent}% · ${row.hint(snapshot.remainingFor(row, now))}", style = RisoType.small.copy(color = inks.text.copy(alpha = 0.7f)), maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))

        // The one big action.
        CoralPill("Plan a leave", "See what a day off would cost") { onOpen(Route.LeavePlanner) }

        // What's due, as a stack of cards.
        val today = now.toLocalDate()
        val due = snapshot.assignments.flatMap { c -> c.items.map { c to it } }
            .filter { (_, a) -> a.updated == null }
            .mapNotNull { (c, a) -> vtopDate(a.due)?.takeIf { !it.isBefore(today) }?.let { Triple(c, a, it) } }
            .sortedBy { it.third }
        if (due.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            BasicText("Due soon", style = RisoType.display(22.sp).copy(color = inks.text, fontWeight = FontWeight.Normal))
            Spacer(Modifier.height(10.dp))
            StackedDeck(
                due.take(5).mapIndexed { i, (c, a, d) ->
                    val days = ChronoUnit.DAYS.between(today, d)
                    DeckItem(
                        a.title, c.courseName,
                        when (days) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "In $days days" },
                        DeckColours[i % DeckColours.size],
                        onClick = { onOpen(Route.Assignment(c.classId, a.code.ifEmpty { "#$i" })) },
                    )
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Attendance in the Soft look: every course as a card in a stack; tap one to open it. */
@Composable
internal fun SoftAttendance(snapshot: Snapshot) {
    val inks = LocalInks.current
    val nav = LocalNav.current
    val now = remember { LocalDateTime.now() }
    val rows = snapshot.attendance.sortedBy { it.percent }
    TabColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("Attendance", style = RisoType.display(30.sp).copy(color = inks.text, fontWeight = FontWeight.Normal), modifier = Modifier.weight(1f))
            CircleButton(RisoIcon.Today, "Plan a leave") { nav.go(Route.LeavePlanner) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip("Threshold 75%", solid = !snapshot.attendanceExempt)
            InkChip("${rows.count { it.atRisk }} at risk")
            InkChip("${rows.count { !it.atRisk }} safe")
        }
        Spacer(Modifier.height(18.dp))
        if (rows.isEmpty()) {
            InkText("VTOP has no attendance for ${snapshot.semester.name} yet.")
            return@TabColumn
        }
        StackedDeck(
            rows.mapIndexed { i, row ->
                val remaining = snapshot.remainingFor(row, now)
                DeckItem(
                    row.courseName + if (isLabType(row.classType)) " · Lab" else "",
                    "${row.attended}/${row.total} · ${row.hint(remaining)}",
                    "${row.percent}%",
                    if (row.atRisk && !snapshot.attendanceExempt) Kind.Vtop.pastel else DeckColours[i % DeckColours.size],
                    big = true,
                    open = {
                        HalftoneBar(row.attended / row.total.coerceAtLeast(1).toFloat(), threshold = 0.75f)
                        snapshot.feeds.remarkFor(row)?.let {
                            Spacer(Modifier.height(10.dp))
                            InkText("VTOP: ${it.remark}", style = RisoType.small.copy(fontWeight = FontWeight.Medium))
                        }
                    },
                )
            },
            expandable = true,
        )
    }
}

/** The welcome in the Soft look: the app's words stacked large on coral, a yellow star, and the way in. */
@Composable
internal fun SoftIntro(onStart: () -> Unit) {
    val inks = LocalInks.current
    val coral = inks.a
    val deep = lerp(coral, Color.Black, 0.16f)
    Box(Modifier.fillMaxSize().background(coral)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 18.dp)) {
            listOf("Attendance", "Timetable", "Assignments", "Marks", "Calendar", "Navigation", "Reminders", "Hostel", "Grades").forEach {
                BasicText(it, style = RisoType.display(52.sp).copy(color = deep, fontWeight = FontWeight.Normal, lineHeight = 52.sp), maxLines = 1, softWrap = false)
            }
        }
        // The star, half off the page.
        Sparkle(Color(0xFFF8D58C), Modifier.size(360.dp).offset((-80).dp, 330.dp))
        Column(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 26.dp)) {
            BasicText("daVIT.", style = RisoType.display(64.sp).copy(color = Color(0xFF1A1A1A), fontWeight = FontWeight.Normal))
            Spacer(Modifier.height(4.dp))
            BasicText("VTOP, calmly.", style = RisoType.body.copy(color = Color(0xFF1A1A1A)))
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Color(0xFF1A1A1A), RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onStart)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                BasicText("Get started", Modifier.align(Alignment.Center), style = RisoType.button.copy(color = Color(0xFF1A1A1A), fontSize = 16.sp))
                BasicText("›", Modifier.align(Alignment.CenterEnd), style = RisoType.button.copy(color = Color(0xFF1A1A1A), fontSize = 20.sp))
            }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------

private val DeckColours = listOf(Kind.Exams.pastel, Kind.Classes.pastel, Kind.Events.pastel, Kind.Reminders.pastel, Kind.Attendance.pastel, Kind.People.pastel)

/** One card in a [StackedDeck]: [open] is what it shows when tapped open. */
internal class DeckItem(
    val title: String,
    val sub: String,
    val trailing: String?,
    val colour: Color,
    val big: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val open: (@Composable () -> Unit)? = null,
)

/**
 * Cards stacked like a wallet: each tucks under the next, so only its top shows, and the
 * last lies open. With [expandable], tapping one opens it instead.
 */
@Composable
internal fun StackedDeck(items: List<DeckItem>, expandable: Boolean = false) {
    val inks = LocalInks.current
    var opened by rememberSaveable { mutableIntStateOf(items.lastIndex) }
    val tuck = 26.dp
    Layout(
        content = {
            items.forEachIndexed { i, item ->
                val open = !expandable || i == opened || i == items.lastIndex
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (inks.dark) lerp(inks.surface, item.colour, 0.3f) else item.colour)
                        .clickable(role = Role.Button) { if (expandable) opened = i else item.onClick?.invoke() }
                        .animateContentSize()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp + if (i < items.lastIndex) tuck else 0.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(item.title, style = RisoType.title, maxLines = 1)
                            if (open) InkText(item.sub, style = RisoType.small.copy(color = inks.text.copy(alpha = 0.75f)), maxLines = 2)
                        }
                        item.trailing?.let {
                            Spacer(Modifier.width(10.dp))
                            if (item.big) BasicText(it, style = RisoType.display(30.sp).copy(color = inks.text))
                            else Pill(it)
                        }
                    }
                    if (open && expandable && item.open != null) {
                        Spacer(Modifier.height(12.dp))
                        item.open.invoke()
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val overlap = tuck.roundToPx()
        var y = 0
        val tops = placeables.mapIndexed { i, p ->
            val top = y
            y += p.height - if (i < placeables.lastIndex) overlap else 0
            top
        }
        layout(constraints.maxWidth, y.coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p -> p.place(0, tops[i]) }
        }
    }
}

@Composable
private fun Pill(text: String) {
    val inks = LocalInks.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, inks.text.copy(alpha = 0.25f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { BasicText(text, style = RisoType.small.copy(color = inks.text, fontSize = 13.sp), maxLines = 1) }
}

@Composable
private fun CircleButton(icon: RisoIcon, label: String, size: Dp = 44.dp, onClick: () -> Unit) {
    val inks = LocalInks.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, inks.text.copy(alpha = 0.25f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon, inks.text, Modifier.size(size * 0.45f)) }
}

@Composable
private fun Bubble(text: String, colour: Color) {
    val inks = LocalInks.current
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(colour).border(2.dp, inks.fill(Kind.Assignments), CircleShape),
        contentAlignment = Alignment.Center,
    ) { BasicText(text.take(2), style = RisoType.small.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A1A1A))) }
}

/** The big coral button: a title and a quiet line under it. */
@Composable
private fun CoralPill(title: String, sub: String, onClick: () -> Unit) {
    val inks = LocalInks.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(inks.a)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(title, style = RisoType.button.copy(color = Color(0xFF1A1A1A), fontSize = 17.sp))
        BasicText(sub, style = RisoType.small.copy(color = Color(0xFF1A1A1A).copy(alpha = 0.7f), fontSize = 12.sp))
    }
}

/** A four-pointed star, its sides drawn in towards the middle. */
@Composable
internal fun Sparkle(colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val p = Path().apply {
            moveTo(c.x, c.y - r)
            quadraticTo(c.x, c.y, c.x + r, c.y)
            quadraticTo(c.x, c.y, c.x, c.y + r)
            quadraticTo(c.x, c.y, c.x - r, c.y)
            quadraticTo(c.x, c.y, c.x, c.y - r)
            close()
        }
        drawPath(p, colour)
    }
}

/** "Applied Science" → "AS". */
private fun initials(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() && it.lowercase() !in setOf("and", "of", "the", "to", "in", "for") }.take(2).joinToString("") { it.first().uppercase() }

/** The display card on Soft Home: rounded like the other sheets. */
private val SoftCardShape = RoundedCornerShape(26.dp)

/** Chips that wrap onto as many lines as they need. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(spacing: Dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) { content() }
}
