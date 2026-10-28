package dev.medriid.davit.ui

import java.time.format.DateTimeFormatter
import java.time.YearMonth
import java.time.LocalDate
import dev.medriid.davit.data.AcademicCalendar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.ClassSlot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The week as one day at a time; the grid view and calendar come in M4. */
@Composable
internal fun WeekTab(snapshot: Snapshot) {
    val week = remember(snapshot) { weekOf(snapshot) }
    val now = LocalDateTime.now()
    val today = now.dayOfWeek.value
    val days = (1..7).filter { it <= 6 || week[it].orEmpty().isNotEmpty() }
    var day by rememberSaveable { mutableIntStateOf(if (today in days) today else days.first()) }
    val inks = LocalInks.current
    var mode by rememberSaveable { mutableIntStateOf(0) }

    TabColumn {
        Row(Modifier.padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading("Week", 28.sp, Modifier.weight(1f))
        }
        ChoiceRow(listOf("Day", "Grid", "Calendar"), mode) { mode = it }
        Spacer(Modifier.height(12.dp))
        if (mode == 1) {
            WeekGrid(week, days, today, now.hour * 60 + now.minute, snapshot.attendanceExempt) {
                day = it
                mode = 0
            }
            return@TabColumn
        }
        if (mode == 2) {
            CalendarView(snapshot.calendar, now.toLocalDate())
            return@TabColumn
        }
        ChoiceRow(days.map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }, days.indexOf(day)) {
            day = days[it]
        }
        Spacer(Modifier.height(16.dp))
        val classes = week[day].orEmpty()
        if (classes.isEmpty()) {
            InkText(if (snapshot.timetable.isEmpty()) "No timetable for ${snapshot.semester.name} yet." else "No classes.")
        }
        val minute = now.hour * 60 + now.minute
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            classes.forEach { slot ->
                val m = slot.meeting
                val live = day == today && minute >= m.start && minute < m.end
                val done = day == today && minute >= m.end
                InkCard(
                    Modifier.fillMaxWidth(),
                    plate = if (live) inks.a else inks.b,
                    plateOffset = if (live) 5.dp else 3.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(64.dp)) {
                            InkText(clock(m.start), style = RisoType.title.copy(fontSize = 17.sp))
                            InkText(clock(m.end), style = RisoType.small)
                        }
                        Column(Modifier.weight(1f)) {
                            InkText(slot.course.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            InkText(
                                listOf(slot.course.venue, m.slot, if (slot.isLab) "Lab" else "Theory")
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = RisoType.small,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            live -> InkChip("Live", solid = true)
                            done -> InkChip("Done")
                            else -> slot.attendance?.let { InkChip("${it.percent}%") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mon–Sat side by side, each class a block at its real time; at-risk courses print in
 * ink A. A line marks now in today's column. Tapping a block opens that day.
 */
@Composable
private fun WeekGrid(week: Map<Int, List<ClassSlot>>, days: List<Int>, today: Int, minute: Int, exempt: Boolean, onDay: (Int) -> Unit) {
    val inks = LocalInks.current
    val all = days.flatMap { week[it].orEmpty() }
    if (all.isEmpty()) {
        InkText("No classes.")
        return
    }
    val startH = all.minOf { it.meeting.start } / 60
    val endH = (all.maxOf { it.meeting.end } + 59) / 60
    val hour = 54.dp
    val head = 24.dp
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(30.dp)) {
            Spacer(Modifier.height(head))
            for (h in startH until endH) Box(Modifier.height(hour)) { InkText("%02d".format(h), style = RisoType.small) }
        }
        days.forEach { d ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(head), contentAlignment = Alignment.Center) {
                    InkText(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.ENGLISH), label = true)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(hour * (endH - startH))
                        .background(if (d == today) inks.b.copy(alpha = 0.07f) else Color.Transparent),
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val hp = hour.toPx()
                        for (i in 0..(endH - startH)) drawLine(inks.rule, Offset(0f, i * hp), Offset(size.width, i * hp), 1.dp.toPx())
                        if (d == today && minute in startH * 60..endH * 60) {
                            val y = (minute - startH * 60) / 60f * hp
                            drawLine(inks.a, Offset(0f, y), Offset(size.width, y), 2.5.dp.toPx())
                        }
                    }
                    week[d].orEmpty().forEach { slot ->
                        val m = slot.meeting
                        val risk = slot.attendance?.atRisk == true && !exempt
                        Box(
                            Modifier
                                .padding(top = hour * ((m.start - startH * 60) / 60f))
                                .padding(horizontal = 2.dp)
                                .fillMaxWidth()
                                .height(hour * ((m.end - m.start) / 60f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (risk) inks.a else inks.b)
                                .clickable(role = Role.Button) { onDay(d) }
                                .padding(3.dp),
                        ) {
                            Column {
                                BasicText(initials(slot.course.courseName), style = RisoType.small.copy(color = inks.paper, fontSize = 11.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                                BasicText(slot.course.venue, style = RisoType.small.copy(color = inks.paper, fontSize = 9.sp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Problem Solving using Python" → "PSP"; short names stay as they are. */
private fun initials(name: String): String {
    val words = name.split(' ', '-').filter { it.isNotBlank() && it.lowercase() !in setOf("using", "and", "of", "the", "for", "in", "to", "with") }
    return if (words.size <= 1) name.take(6) else words.take(4).joinToString("") { it.first().uppercase() }
}

/** The semester calendar: days left, exam windows, then a month grid per month with its notes. */
@Composable
private fun CalendarView(cal: AcademicCalendar?, today: LocalDate) {
    val inks = LocalInks.current
    if (cal == null || cal.days.isEmpty()) {
        InkText("No published calendar for this semester yet.")
        return
    }
    val left = cal.instructionalDaysLeft(today)
    InkCard(Modifier.fillMaxWidth()) {
        Heading("$left", 40.sp)
        Spacer(Modifier.height(6.dp))
        InkText(
            "teaching days left" + (cal.lastTheory?.let { " · last day ${it.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}" } ?: ""),
            style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
        )
    }
    val exams = cal.examWindows()
    if (exams.isNotEmpty()) {
        SectionTitle("Exam dates")
        InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
            exams.forEachIndexed { i, (type, from, to) ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        InkText(type, style = RisoType.title)
                        val f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
                        InkText(if (from == to) from.format(f) else "${from.format(f)} – ${to.format(f)}", style = RisoType.small)
                    }
                    val days = from.toEpochDay() - today.toEpochDay()
                    InkChip(
                        when {
                            today.isAfter(to) -> "Done"
                            days <= 0 -> "On now"
                            days == 1L -> "Tomorrow"
                            else -> "$days days"
                        },
                        solid = days in 0..14 && !today.isAfter(to),
                    )
                }
            }
        }
    }
    cal.days.groupBy { YearMonth.from(it.date) }.toSortedMap().forEach { (month, list) ->
        SectionTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { InkText(it, label = true) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val byDate = list.associateBy { it.date }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val date = month.atDay(n)
                            val d = byDate[date]
                            val fill = when {
                                d == null -> Color.Transparent
                                d.exam -> inks.b
                                !d.instructional -> inks.a.copy(alpha = 0.28f)
                                else -> Color.Transparent
                            }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(fill)
                                    .then(if (date == today) Modifier.border(2.dp, inks.text, RoundedCornerShape(6.dp)) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    BasicText(
                                        "$n",
                                        style = RisoType.small.copy(
                                            color = if (d?.exam == true) inks.paper else inks.text,
                                            fontWeight = if (d?.instructional == true) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                    )
                                    if (d != null && d.instructional && d.dayOrder in 1..7 && d.dayOrder != date.dayOfWeek.value) {
                                        BasicText(
                                            DayOfWeek.of(d.dayOrder).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                                            style = RisoType.small.copy(color = inks.a, fontSize = 9.sp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val notes = list.filter { it.note != null }
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            notes.forEach {
                InkText("${it.date.dayOfMonth} · ${it.note}" + if (!it.instructional) " (${it.type})" else "", style = RisoType.small)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    InkText("Calendar data: github.com/IshanJ25/vtop-cal-data", style = RisoType.small.copy(color = inks.rule))
}
