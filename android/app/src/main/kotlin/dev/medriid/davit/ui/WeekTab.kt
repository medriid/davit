package dev.medriid.davit.ui

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

    TabColumn {
        Heading("Week", 28.sp, Modifier.padding(top = 8.dp, bottom = 14.dp))
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
