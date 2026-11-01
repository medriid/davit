package dev.medriid.davit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.FacultyBook
import dev.medriid.davit.data.FacultyReview
import dev.medriid.davit.data.ReviewedFaculty
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.RisoType
import java.util.Locale

@Composable
private fun rememberFacultyBook(): FacultyBook? {
    val store = LocalRepo.current.facultyReviews
    LaunchedEffect(Unit) { store.load() }
    return store.book.collectAsState().value
}

private fun ratingText(rating: Double) = String.format(Locale.US, "%.1f", rating)
private fun reviewsText(n: Int) = if (n == 1) "1 review" else "$n reviews"

private fun sourceText(source: String) = when (source) {
    "reddit" -> "From Reddit"
    "compiled" -> "Compiled by No8AM"
    else -> "Written on No8AM"
}

/** "Rated 4.2 · 12 reviews" for a teacher VTOP shows, if No8AM has reviews of them. */
@Composable
internal fun FacultyRatingLink(empId: String?, name: String, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val found = rememberFacultyBook()?.find(empId, name)?.takeIf { it.reviewCount > 0 } ?: return
    Box(modifier.clickable(role = Role.Button) { nav.go(Route.FacultyReview(found.id)) }) {
        InkChip("Rated ${ratingText(found.rating)} · ${reviewsText(found.reviewCount)}")
    }
}

/** Every teacher No8AM lists, by name, department or course. */
@Composable
internal fun FacultyReviewsScreen(route: Route) {
    val nav = LocalNav.current
    val book = rememberFacultyBook()
    var query by rememberSaveable { mutableStateOf("") }
    var campus by rememberSaveable { mutableStateOf("vellore") }
    RouteScreen(route, "Faculty reviews", "Student ratings from No8AM. Works offline.") {
        SearchField(query, { query = it }, {}, autoFocus = false)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("vellore" to "Vellore", "chennai" to "Chennai").forEach { (id, label) ->
                Box(Modifier.clickable(role = Role.Button) { campus = id }) { InkChip(label, solid = campus == id) }
            }
        }
        if (book == null) {
            Loading("Opening the reviews")
            return@RouteScreen
        }
        val results = remember(book, query, campus) { book.search(query, campus) }
        SectionTitle(if (query.isBlank()) "Most reviewed" else "${results.size} found")
        if (results.isEmpty()) InkText("Nobody matches \"$query\".", style = RisoType.small)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            results.take(40).forEach { f ->
                InkCard(
                    Modifier.fillMaxWidth(),
                    kind = dev.medriid.davit.design.Kind.People,
                    onClick = { nav.go(Route.FacultyReview(f.id)) },
                    plateOffset = 3.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(personName(f.name), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            InkText(f.department, style = RisoType.small, maxLines = 2)
                            InkText(if (f.reviewCount > 0) reviewsText(f.reviewCount) else "No reviews yet", style = RisoType.small)
                        }
                        if (f.reviewCount > 0) {
                            Spacer(Modifier.width(10.dp))
                            InkText(ratingText(f.rating), style = RisoType.title.copy(fontSize = 22.sp))
                        }
                    }
                }
            }
            if (results.size > 40) InkText("${results.size - 40} more; type a name, department or course code.", style = RisoType.small)
        }
    }
}

/** One teacher: their rating, how it splits, and every review, newest first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FacultyReviewScreen(route: Route.FacultyReview) {
    val book = rememberFacultyBook()
    val f = book?.byId(route.facultyId)
    if (f == null) {
        RouteScreen(route, "Faculty review") { if (book == null) Loading("Opening the reviews") else InkText("This teacher isn't in the reviews.") }
        return
    }
    RouteScreen(route, personName(f.name), listOf(f.designation, f.department).filter { it.isNotBlank() }.joinToString(" · ")) {
        if (f.reviews.isEmpty()) {
            InkText("No reviews yet.", modifier = Modifier.padding(top = 8.dp))
            return@RouteScreen
        }
        RatingSummary(f)
        val courses = remember(f) { f.reviews.map { it.course }.filter { it.isNotBlank() }.distinct().sorted() }
        var course by rememberSaveable(f.id) { mutableStateOf<String?>(null) }
        if (courses.size > 1) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clickable(role = Role.Button) { course = null }) { InkChip("All", solid = course == null) }
                courses.forEach { c -> Box(Modifier.clickable(role = Role.Button) { course = c }) { InkChip(c, solid = course == c) } }
            }
        }
        val shown = remember(f, course) { f.reviews.filter { course == null || it.course == course }.sortedByDescending { it.date } }
        SectionTitle(reviewsText(shown.size))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { shown.forEach { ReviewCard(it) } }
        InkText(
            "From No8AM, as of 1 November 2026. These are students' opinions, not checked by this app.",
            style = RisoType.small,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun RatingSummary(f: ReviewedFaculty) {
    val counts = remember(f) { f.reviews.groupingBy { it.rating }.eachCount() }
    val most = (counts.values.maxOrNull() ?: 1).toFloat()
    InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.People, plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(92.dp)) {
                InkText(ratingText(f.rating), style = RisoType.title.copy(fontSize = 34.sp))
                InkText("of 5", style = RisoType.small)
                InkText(reviewsText(f.reviewCount), style = RisoType.small)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (5 downTo 1).forEach { star ->
                    val n = counts[star] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkText("$star", style = RisoType.small, modifier = Modifier.width(16.dp))
                        Box(Modifier.weight(1f)) { HalftoneBar(n / most, height = 10.dp) }
                        InkText("$n", style = RisoType.small, modifier = Modifier.padding(start = 8.dp).width(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(r: FacultyReview) {
    InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.People, plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(r.course.ifBlank { "Course not given" }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                if (r.courseName.isNotBlank()) InkText(r.courseName, style = RisoType.small, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            InkChip("${r.rating} of 5", solid = true)
        }
        Spacer(Modifier.height(8.dp))
        InkText(r.text)
        val cutoffs = listOf("s", "a", "b", "c", "d", "e", "f").mapNotNull { g -> r.cutoffs[g]?.let { "${g.uppercase()} $it" } }
        if (cutoffs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            InkText("Cutoffs: " + cutoffs.joinToString(" · "), style = RisoType.small)
        }
        val stats = listOfNotNull(
            r.classStrength?.let { "Class of $it" },
            r.classMean?.let { "Mean $it" },
            r.classSd?.let { "SD $it" },
        )
        if (stats.isNotEmpty()) InkText(stats.joinToString(" · "), style = RisoType.small)
        r.replies.forEach { reply ->
            Spacer(Modifier.height(8.dp))
            Hairline()
            Spacer(Modifier.height(8.dp))
            InkText("Reply · ${reply.date}", label = true)
            InkText(reply.text, style = RisoType.small)
        }
        Spacer(Modifier.height(8.dp))
        InkText(
            listOfNotNull(r.date.ifBlank { null }, sourceText(r.source), "Paraphrased".takeIf { r.paraphrased }).joinToString(" · "),
            style = RisoType.small,
        )
    }
}
