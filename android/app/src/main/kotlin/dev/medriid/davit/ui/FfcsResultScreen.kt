package dev.medriid.davit.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.Section
import dev.medriid.davit.data.asPicks
import dev.medriid.davit.data.clashes
import dev.medriid.davit.data.courseName
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.RisoType

/** Your FFCS result: the classes VTOP has you registered for next semester, and their week. Read only. */
@Composable
internal fun FfcsResultScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    val busy = Section.Registered in loading
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Registered) }

    val result = campus.registered
    RouteScreen(
        route, "FFCS result", result?.semester?.name ?: "What you registered for next semester.",
        refreshing = busy, onRefresh = { repo.campus.refresh(Section.Registered, force = true) },
    ) {
        errors[Section.Registered]?.let { ErrorNote(it) { repo.campus.refresh(Section.Registered, force = true) } }
        val courses = result?.courses.orEmpty()
        if (courses.isEmpty()) {
            when {
                busy -> Loading("Reading your registration")
                errors[Section.Registered] == null -> InkText("No registered classes for next semester yet. They show here once you've registered on VTOP.")
            }
            return@RouteScreen
        }

        val picks = courses.asPicks()
        val clashes = picks.clashes()
        val codes = courses.groupBy { it.courseCode }
        InkText(
            "${codes.size} course${if (codes.size == 1) "" else "s"} · ${creditsText(courses.sumOf { it.credits })} credits · " +
                if (clashes.isEmpty()) "no clashes" else "${clashes.size} clash${if (clashes.size == 1) "" else "es"}",
            style = RisoType.title,
        )
        InkText(
            if (courses.all { it.approved }) "Registered and approved on VTOP." else "Registered on VTOP, not marked approved yet.",
            style = RisoType.small,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (clashes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ClashCard(clashes)
        }

        GroupLabel("Your classes")
        codes.forEach { (code, classes) ->
            InkText(
                "$code ${state.aliases.courseName(code, classes.first().courseName)}",
                style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                modifier = Modifier.padding(top = 8.dp),
            )
            classes.forEach { c ->
                InkText(
                    listOf(c.courseType, c.slot.ifBlank { "No slot" }, c.venue, c.faculty, "${creditsText(c.credits)} cr").filter { it.isNotBlank() }.joinToString(" · "),
                    style = RisoType.small,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (picks.any { it.meetings.isNotEmpty() }) {
            GroupLabel("Your week")
            PlanWeek(picks)
        }
    }
}

private fun creditsText(credits: Double) = if (credits % 1.0 == 0.0) credits.toInt().toString() else credits.toString()
