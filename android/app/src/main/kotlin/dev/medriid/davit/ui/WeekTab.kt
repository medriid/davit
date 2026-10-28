package dev.medriid.davit.ui

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
    var grid by rememberSaveable { mutableStateOf(false) }

    TabColumn {
        Row(Modifier.padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading("Week", 28.sp, Modifier.weight(1f))
            ChoiceRow(listOf("Day", "Grid"), if (grid) 1 else 0) { grid = it == 1 }
        }
        if (grid) {
            WeekGrid(week, days, today, now.hour * 60 + now.minute, snapshot.attendanceExempt) {
                day = it
                grid = false
            }
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
