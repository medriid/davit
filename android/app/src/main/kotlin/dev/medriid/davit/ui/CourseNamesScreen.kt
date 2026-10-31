package dev.medriid.davit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType

/**
 * Your names for courses: "Python" for "Problem Solving and Programming in Python". They
 * show everywhere the course does (Home, attendance, the week, marks, widgets,
 * notifications). Leave one empty for VTOP's name.
 */
@Composable
internal fun CourseNamesScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val inks = LocalInks.current
    val courses = remember(state.snapshot?.syncedAt) { repo.vtopCourses() }
    RouteScreen(route, "Course names", "Call each course what you call it. Your names show everywhere in daVIT; leave one empty for VTOP's.") {
        if (courses.isEmpty()) {
            InkText("Your courses show here after the first sync.")
            return@RouteScreen
        }
        if (inks.editorial) {
            EditorialNameRows(courses, state.aliases, repo::setAlias)
            return@RouteScreen
        }
        courses.forEach { (code, vtop) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                var name by remember(code) { mutableStateOf(state.aliases[code].orEmpty()) }
                InkText("$code · $vtop", style = RisoType.small.copy(color = inks.text.copy(alpha = 0.6f)), maxLines = 2)
                Spacer(Modifier.height(6.dp))
                InkField(
                    name,
                    {
                        name = it.take(40)
                        repo.setAlias(code, name)
                    },
                    "Your name for it",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
            }
        }
    }
}
