package dev.medriid.davit.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.vtop.FacultyHit
import dev.medriid.davit.vtop.FacultyInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What VTOP's faculty search is sent for [query]: letters and single spaces, lower case. */
internal fun facultyQuery(query: String): String =
    query.filter { it.isLetter() || it == ' ' }.trim().replace(Regex(" +"), " ").lowercase()

/**
 * Anyone VTOP lists, by name: VTOP's own Faculty Info search, run once you've typed three
 * letters and paused. Tap a name for their designation, cabin and email.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VtopFacultySearch(query: String) {
    val repo = LocalRepo.current
    val q = facultyQuery(query)
    if (q.count { it.isLetter() } < 3) return
    val results = remember { mutableStateMapOf<String, Result<List<FacultyHit>>>() }
    LaunchedEffect(q) {
        if (q in results) return@LaunchedEffect
        delay(650)
        results[q] = runCatching { repo.onVtop { c -> c.searchFaculty(q) } }
    }
    SectionTitle("Anyone on VTOP")
    val r = results[q]
    when {
        r == null -> Loading("Searching VTOP for \"$q\"")
        r.isFailure -> ErrorNote(repo.explain(r.exceptionOrNull()!!)) { results.remove(q) }
        r.getOrThrow().isEmpty() -> InkText("VTOP has nobody called \"$q\".", style = RisoType.small)
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.getOrThrow().take(30).forEach { hit -> FacultyHitCard(hit) }
            if (r.getOrThrow().size > 30) InkText("${r.getOrThrow().size - 30} more; type more of the name.", style = RisoType.small)
        }
    }
}

/** Details are fetched once, on the first tap. */
private val facultyCards = mutableStateMapOf<String, FacultyInfo?>()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacultyHitCard(hit: FacultyHit) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(hit.empId) { mutableStateOf(false) }
    var loading by remember(hit.empId) { mutableStateOf(false) }
    var error by remember(hit.empId) { mutableStateOf<String?>(null) }
    val card = facultyCards[hit.empId]
    InkCard(
        Modifier.fillMaxWidth(),
        kind = dev.medriid.davit.design.Kind.People,
        onClick = {
            open = !open
            if (open && hit.empId !in facultyCards && !loading) {
                loading = true
                error = null
                scope.launch {
                    runCatching { repo.onVtop { c -> c.faculty(hit.empId) } }
                        .onSuccess { facultyCards[hit.empId] = it }
                        .onFailure { error = repo.explain(it) }
                    loading = false
                }
            }
        },
        plateOffset = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(personName(hit.name), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(listOf(hit.designation, hit.school).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            InkChip(if (open) "Less" else "Cabin")
        }
        if (!open) return@InkCard
        Spacer(Modifier.height(8.dp))
        when {
            loading -> InkText("Opening their card on VTOP", style = RisoType.small)
            error != null -> InkText(error!!, style = RisoType.small.copy(color = LocalInks.current.a))
            card == null && hit.empId in facultyCards -> InkText("VTOP didn't open this card.", style = RisoType.small)
            card != null -> {
                if (card.department.isNotBlank()) InkText(card.department, style = RisoType.small)
                Spacer(Modifier.height(6.dp))
                if (card.cabin.isBlank() && card.email.isBlank()) InkText("No cabin or email on VTOP.", style = RisoType.small)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.cabin.isNotBlank()) dev.medriid.davit.ui.map.MapChip(card.cabin, label = "Cabin ${card.cabin}")
                    if (card.email.isNotBlank()) {
                        Box(Modifier.clickable(role = Role.Button) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + card.email)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { InkChip(card.email, solid = true) }
                    }
                }
            }
        }
    }
}

/** "Dr. ASHA RAMAN" → "Dr. Asha Raman". */
internal fun personName(s: String): String {
    val title = Regex("""^(Dr|Mr|Ms|Mrs|Prof)\.?\s+""", RegexOption.IGNORE_CASE).find(s)
    val rest = titleCase(s.removeRange(0, title?.range?.last?.plus(1) ?: 0))
    return if (title != null) title.groupValues[1].lowercase().replaceFirstChar { it.uppercase() } + ". " + rest else rest
}
