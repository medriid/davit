package dev.medriid.davit.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import dev.medriid.davit.design.rememberMotionEnabled

/**
 * Screens above the tabs. Each has a string id, so the stack survives rotation and the
 * system killing the app in the background.
 */
sealed class Route(val id: String) {
    data class Assignment(val classId: String, val code: String) : Route("a|$classId|$code")
    data class Course(val key: String) : Route("c|$key")
    data object Search : Route("search")
    data object Campus : Route("campus")
    data object Messages : Route("messages")
    data object Spotlight : Route("spotlight")
    data object Events : Route("events")
    data object People : Route("people")
    data object Money : Route("money")
    data object Gate : Route("gate")
    data object Curriculum : Route("curriculum")
    data object SemesterGrades : Route("grades")
    data object Hostel : Route("hostel")
    data object Downloads : Route("downloads")
    data object Settings : Route("settings")
    data object Notifications : Route("notifications")
    data object Bonafide : Route("bonafide")
    data object CourseFeedback : Route("feedback")
    data object PaperSeeing : Route("paperseeing")
    data object Activities : Route("activities")
    data object Allocation : Route("allocation")
    data object Colours : Route("colours")
    data object LeavePlanner : Route("leave")
    data object DisplayCard : Route("displaycard")
    data object CourseNames : Route("coursenames")

    /** The shared-element key this route's screen grows out of. */
    val sharedKey: String get() = "route:$id"

    companion object {
        private val plain = listOf(Search, Campus, Messages, Spotlight, Events, People, Money, Gate, Curriculum, SemesterGrades, Hostel, Downloads, Settings, Notifications, Bonafide, CourseFeedback, PaperSeeing, Activities, Allocation, Colours, LeavePlanner, DisplayCard, CourseNames)

        fun parse(id: String): Route? {
            plain.firstOrNull { it.id == id }?.let { return it }
            val p = id.split('|')
            return when (p.firstOrNull()) {
                "a" -> if (p.size >= 3) Assignment(p[1], p[2]) else null
                "c" -> if (p.size >= 2) Course(id.removePrefix("c|")) else null
                else -> null
            }
        }

        val StackSaver = listSaver<SnapshotStateList<Route>, String>(
            save = { s -> s.map { it.id } },
            restore = { ids -> ids.mapNotNull(::parse).toMutableStateList() },
        )
    }
}

/** Opening and closing screens. */
class Nav(private val stack: SnapshotStateList<Route>, private val onTab: (Int) -> Unit) {
    val top: Route? get() = stack.lastOrNull()

    fun go(route: Route) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Closes every screen and shows tab [index]. */
    fun tab(index: Int) {
        stack.clear()
        onTab(index)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no Nav") }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }

val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The container transform: an element marked with [key] in a list and the screen marked
 * with the same key grow into each other. Nothing when animations are off.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(key: String): Modifier {
    val shared = LocalShared.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    if (!rememberMotionEnabled()) return this
    return with(shared) {
        this@sharedCard.sharedBounds(
            rememberSharedContentState(key),
            anim,
            // Fade through: the old content clears fast, then the new one prints in as it grows.
            enter = fadeIn(tween(240, delayMillis = 80)),
            exit = fadeOut(tween(90)),
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 420f) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
