package dev.medriid.davit.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Section
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.vtop.Exam
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The exam season at a glance: the coming group (CAT1, CAT2, FAT) paper by paper, with the
 * time, room and seat, how far off each is, the free days between them, and the course's
 * materials a tap away.
 */
@Composable
internal fun ExamBoardScreen(route: Route) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Courses) }
    val s = state.snapshot
    val today = remember { LocalDate.now() }
    val dated = s?.exams.orEmpty().mapNotNull { e -> e.day()?.let { e to it } }.sortedBy { it.second }
    // The group of the next paper; once a season is over, the last one.
    val group = (dated.firstOrNull { !it.second.isBefore(today) } ?: dated.lastOrNull())?.first?.type.orEmpty()
    val papers = dated.filter { it.first.type == group }
    val left = papers.count { !it.second.isBefore(today) }

    RouteScreen(
        route,
        "Exam board",
        if (papers.isEmpty()) "Your exam schedule shows here once VTOP publishes it."
        else "${group.ifBlank { "Exams" }} · ${papers.size} paper${if (papers.size == 1) "" else "s"}, $left to go · " +
            "${papers.first().second.format(Short)} to ${papers.last().second.format(Short)}",
    ) {
        if (papers.isEmpty()) return@RouteScreen
        var previous: LocalDate? = null
        papers.forEachIndexed { i, (exam, day) ->
            previous?.let { prev ->
                val free = ChronoUnit.DAYS.between(prev, day).toInt() - 1
                run {
                    InkText(
                        when (free) {
                            -1 -> "The same day"
                            0 -> "The next day"
                            1 -> "1 free day"
                            else -> "$free free days"
                        },
                        style = RisoType.small.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
            previous = day
            Paper(exam, day, today, Modifier.printIn(i).padding(bottom = 4.dp), onMaterials = {
                val course = campus.courses.firstOrNull { it.code == exam.courseCode && it.semesterId == s?.semester?.id }
                    ?: campus.courses.firstOrNull { it.code == exam.courseCode }
                if (course != null) nav.go(Route.Course(course.key)) else {
                    repo.academicsFocus.value = 4
                    nav.tab(2)
                }
            }, onCalendar = { addToCalendar(context, exam) })
        }
        val others = dated.map { it.first.type }.distinct().filter { it != group }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            InkText("Also on VTOP: " + others.joinToString(", ") + ". All of them are in Academics → Exams.", style = RisoType.small)
        }
    }
}

@Composable
private fun Paper(exam: Exam, day: LocalDate, today: LocalDate, modifier: Modifier, onMaterials: () -> Unit, onCalendar: () -> Unit) {
    val days = ChronoUnit.DAYS.between(today, day).toInt()
    val done = days < 0
    InkCard(modifier.fillMaxWidth().alpha(if (done) 0.55f else 1f), kind = Kind.Exams, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(64.dp)) {
                Heading(day.dayOfMonth.toString(), 30.sp)
                InkText(day.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)), style = RisoType.small)
                InkText(day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)), style = RisoType.small)
            }
            Column(Modifier.weight(1f)) {
                InkText(exam.courseName, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(listOf(exam.time, exam.session).filter { it.isNotBlank() }.distinct().joinToString(" · "), style = RisoType.small)
                // VTOP writes "-" until rooms and seats are out.
                fun known(v: String) = v.trim().takeIf { it.isNotEmpty() && it != "-" }
                InkText(listOfNotNull(known(exam.venue), known(exam.seat)?.let { "seat $it" }).joinToString(" · ").ifBlank { "Room and seat not out yet" }, style = RisoType.small)
            }
            Spacer(Modifier.width(8.dp))
            InkChip(
                when {
                    done -> "Done"
                    days == 0 -> "Today"
                    days == 1 -> "Tomorrow"
                    else -> "$days days"
                },
                solid = !done && days <= 3,
            )
        }
        if (!done) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clickable(role = Role.Button, onClick = onMaterials)) { InkChip("Materials") }
                Box(Modifier.clickable(role = Role.Button, onClick = onCalendar)) { InkChip("Add to calendar") }
            }
        }
    }
}

private val Short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
