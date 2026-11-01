package dev.medriid.davit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.CachedCourse
import dev.medriid.davit.data.courseName
import dev.medriid.davit.data.FilterOption
import dev.medriid.davit.data.MaterialFilter
import dev.medriid.davit.data.MaterialResults
import dev.medriid.davit.data.MaterialRow
import dev.medriid.davit.data.MaterialsStore
import dev.medriid.davit.data.Section
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.data.searchMaterials
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.EditorialShape
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
import dev.medriid.davit.search.Fuzzy
import dev.medriid.davit.vtop.CourseOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- Academics → Materials -------------------------------------------------------------

/** Your course pages, this semester first: what's there, how much is your faculty's, what's saved. */
@Composable
internal fun MaterialsSegment(snapshot: Snapshot) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    val pages by repo.materials.pages.collectAsState()
    val saved by repo.files.saved.collectAsState()
    val aliases = repo.state.collectAsState().value.aliases
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Courses) }

    SearchLauncher(Modifier.sharedCard(Route.Search.sharedKey)) { nav.go(Route.Search) }
    Spacer(Modifier.height(14.dp))
    val courses = campus.courses
    errors[Section.Courses]?.let { ErrorNote(it) { repo.campus.refresh(Section.Courses, force = true) } }
    if (courses.isEmpty()) {
        if (Section.Courses in loading) Loading("Reading your course pages from VTOP") else InkText("No course pages on VTOP yet.")
        return
    }
    val order = courses.groupBy { it.semesterId }.entries.sortedBy { if (it.key == snapshot.semester.id) 0 else 1 }
    var i = 0
    order.forEach { (_, list) ->
        GroupLabel(list.first().semesterName.ifBlank { "Courses" })
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list.forEach { c ->
                val cached = pages[c.key]
                val rows = remember(cached, snapshot) { cached?.let { repo.materials.rows(it, snapshot) }.orEmpty() }
                CourseCard(c, aliases.courseName(c.code, c.name), cached, rows.count { it.mine }, rows.count { it.key in saved }, Modifier.printIn(i++)) { nav.go(Route.Course(c.key)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    InkText("Pages open offline once fetched. Downloads stay on this phone until you delete them.", style = RisoType.small.copy(color = LocalInks.current.rule))
}

@Composable
private fun CourseCard(c: CourseOption, name: String, cached: CachedCourse?, mine: Int, saved: Int, modifier: Modifier, onOpen: () -> Unit) {
    InkCard(modifier.fillMaxWidth().sharedCard(Route.Course(c.key).sharedKey), onClick = onOpen, kind = dev.medriid.davit.design.Kind.Assignments, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(name.ifBlank { c.code }, style = RisoType.title, maxLines = 2)
                InkText("${c.code} · ${c.typeName.ifBlank { c.type }}", style = RisoType.small, maxLines = 1)
            }
            if (cached != null) {
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Heading("${cached.page.materials.size}", 26.sp)
                    InkText("files", style = RisoType.small)
                }
            }
        }
        if (cached != null && (mine > 0 || saved > 0)) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mine > 0) InkChip("$mine from your faculty", solid = true)
                if (saved > 0) InkChip("$saved saved")
            }
        }
    }
}

/** Looks like a search field; opens the search screen (which grows out of it). */
@Composable
private fun SearchLauncher(modifier: Modifier, onClick: () -> Unit) {
    val inks = LocalInks.current
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .clip(CardShape)
            .background(inks.paper)
            .border(2.dp, inks.text, CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph()
        Spacer(Modifier.width(10.dp))
        InkText("Search every course's materials", style = RisoType.body.copy(color = inks.rule))
    }
}

@Composable
private fun SearchGlyph() {
    val inks = LocalInks.current
    androidx.compose.foundation.Canvas(Modifier.width(20.dp).height(20.dp)) {
        val w = 2.2.dp.toPx()
        drawCircle(inks.text, size.minDimension * 0.32f, androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(inks.text, androidx.compose.ui.geometry.Offset(size.width * 0.66f, size.height * 0.66f), androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.92f), w, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// ---- one course ------------------------------------------------------------------------

/**
 * One course page: your own faculty's uploads first, by module; everyone else's below,
 * folded. Each file downloads with progress and opens offline after.
 */
@Composable
internal fun CourseScreen(route: Route.Course) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val pages by repo.materials.pages.collectAsState()
    val loading by repo.materials.loading.collectAsState()
    val errors by repo.materials.errors.collectAsState()
    val saved by repo.files.saved.collectAsState()
    val cached = pages[route.key]
    val course = campus.courses.firstOrNull { it.key == route.key } ?: cached?.course
    LaunchedEffect(course) { course?.let { repo.materials.load(it) } }
    if (course == null) {
        RouteScreen(route, "Course page") { InkText("This course isn't on your course pages any more.") }
        return
    }
    val snapshot = state.snapshot
    val rows = remember(cached, snapshot) { cached?.let { repo.materials.rows(it, snapshot) }.orEmpty() }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var othersOpen by rememberSaveable { mutableStateOf(false) }
    val faculty = remember(course, snapshot) { snapshot?.timetable.orEmpty().filter { it.courseCode == course.code }.map { it.faculty }.filter { it.isNotBlank() }.distinct() }
    val shown = when (filter) {
        1 -> rows.filter { it.mine }
        2 -> rows.filter { it.key in saved }
        else -> rows
    }
    val mine = shown.filter { it.mine }
    val others = shown.filterNot { it.mine }

    RouteLazyScreen(
        route, course.code, "${state.aliases.courseName(course.code, course.name)} · ${course.typeName.ifBlank { course.type }}",
        refreshing = route.key in loading && cached != null,
        onRefresh = { repo.materials.load(course, force = true) },
    ) {
        item(key = "top") {
            Column {
                cached?.let { Freshness(it.at) }
                errors[route.key]?.let { ErrorNote(it) { repo.materials.load(course, force = true) } }
                if (cached != null) {
                    ChoiceRow(listOf("All ${rows.size}", "Your faculty ${rows.count { it.mine }}", "Saved ${rows.count { it.key in saved }}"), filter) { filter = it }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        if (cached == null) {
            item(key = "loading") { Loading(if (route.key in loading) "Reading the course page (big ones take a few seconds)" else "Not fetched yet. Pull down to fetch.") }
            return@RouteLazyScreen
        }
        if (rows.isEmpty()) {
            item(key = "none") { InkText("Nobody has uploaded anything here yet.", modifier = Modifier.padding(top = 10.dp)) }
        }
        if (mine.isNotEmpty() || (filter != 2 && faculty.isNotEmpty())) {
            item(key = "mine-head") {
                GroupLabel("Your faculty" + if (faculty.isNotEmpty()) " · " + faculty.joinToString(", ") { titleCase(it) } else "")
            }
            if (mine.isEmpty()) item(key = "mine-none") { InkText("Nothing from your faculty on this page yet.", style = RisoType.small) }
            byModule("mine", mine, cached.page.modules)
        }
        if (others.isNotEmpty()) {
            item(key = "others-head") {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { othersOpen = !othersOpen }.padding(top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InkText(if (mine.isEmpty() && faculty.isEmpty()) "Everyone's uploads" else "Other faculty", label = true, modifier = Modifier.weight(1f))
                    InkChip(if (othersOpen || mine.isEmpty()) "${others.size}" else "Show ${others.size}", solid = !othersOpen && mine.isNotEmpty())
                }
            }
            if (othersOpen || mine.isEmpty()) byModule("others", others, cached.page.modules)
        }
        val savedHere = rows.filter { it.key in saved }
        if (savedHere.isNotEmpty()) {
            item(key = "clear") {
                Column(Modifier.padding(top = 20.dp)) {
                    Rule()
                    Spacer(Modifier.height(10.dp))
                    val haptics = rememberHaptics()
                    var sure by remember { mutableStateOf(false) }
                    Box(Modifier.clickable(role = Role.Button) {
                        if (sure) {
                            savedHere.forEach { repo.files.delete(it.key) }
                            haptics.tick()
                        }
                        sure = !sure
                    }) {
                        InkChip(if (sure) "Tap again to delete ${savedHere.size} saved file${if (savedHere.size > 1) "s" else ""}" else "Delete this course's downloads", solid = sure)
                    }
                }
            }
        }
    }
}

private val shortDay = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Rows grouped under "Module 2 · Energy Devices" headings, module order, then the rest. */
private fun LazyListScope.byModule(prefix: String, rows: List<MaterialRow>, modules: Map<String, String>) {
    val groups = rows.groupBy { it.material.module }.entries.sortedWith(compareBy({ it.key.isEmpty() }, { it.key.toIntOrNull() ?: Int.MAX_VALUE }))
    groups.forEach { (module, list) ->
        item(key = "$prefix-m-$module") {
            val name = modules[module]
            InkText(
                if (module.isEmpty()) "Other" else "Module $module" + (name?.let { " · $it" } ?: ""),
                style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                maxLines = 2,
            )
        }
        list.forEachIndexed { i, r ->
            item(key = "$prefix-f-${r.material.fileId}") {
                Column(Modifier.printIn(i)) {
                    FileRow(r.key, r.material.title, materialLine(r), fetch = { c, out, p -> c.downloadMaterial(r.material.fileId, out, p) })
                    Hairline()
                }
            }
        }
    }
}

private fun materialLine(r: MaterialRow): String = listOfNotNull(
    r.material.kind.ifBlank { null },
    titleCase(r.material.uploader).ifBlank { null },
    r.day?.format(shortDay) ?: r.material.date.ifBlank { null },
    r.material.semester.takeIf { !it.equals(r.course.semesterName, ignoreCase = true) && it.isNotBlank() },
).joinToString(" · ")

/** "ASHA RAMAN" → "Asha Raman", character for character, so match ranges still line up. */
internal fun nameCase(s: String): String = buildString(s.length) {
    s.forEachIndexed { i, c -> append(if (i == 0 || !s[i - 1].isLetter()) c.uppercaseChar() else c.lowercaseChar()) }
}

internal fun titleCase(s: String) = s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
    if (w.length <= 2 && w.all(Char::isUpperCase)) w else w.lowercase().replaceFirstChar { it.uppercase() }
}

// ---- search ------------------------------------------------------------------------------

/**
 * Search across every course's materials: typo-tolerant, by title, module, faculty or
 * course, with highlighted matches. The Filter button narrows by subject code, module and
 * faculty, from what the query leaves; what's set shows under it, each a tap from gone. The
 * index and every search run on a background thread, so typing never waits on it.
 */
@Composable
internal fun SearchScreen(route: Route) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val pages by repo.materials.pages.collectAsState()
    val loading by repo.materials.loading.collectAsState()
    val saved by repo.files.saved.collectAsState()
    val recent by repo.materials.recent.collectAsState()
    val snapshot = state.snapshot

    LaunchedEffect(Unit) { repo.campus.refresh(Section.Courses) }
    // Search covers every course, so fetch this semester's pages that aren't here yet.
    LaunchedEffect(campus.courses) {
        repo.materials.loadMissing(campus.courses.filter { it.semesterId == snapshot?.semester?.id }.ifEmpty { campus.courses })
    }

    var query by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf<String?>(null) }
    var faculty by rememberSaveable { mutableStateOf<String?>(null) }
    var module by rememberSaveable { mutableStateOf<String?>(null) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    val rows = remember(pages, snapshot) { repo.materials.allRows(snapshot) }
    val index by produceState<Fuzzy.Index<MaterialRow>?>(null, rows) {
        value = withContext(Dispatchers.Default) { repo.materials.index(rows) }
    }
    val filter = MaterialFilter(subject, faculty, module, mineOnly, savedOnly)
    val filtered = filter != MaterialFilter()
    // Runs even with nothing typed: the Filter button's options come from it.
    val results by produceState(MaterialResults(), index, query, filter, saved) {
        val idx = index ?: return@produceState
        value = withContext(Dispatchers.Default) { searchMaterials(idx, query, filter, saved.keys) }
    }
    val hits = results.hits
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()

    Box(Modifier.fillMaxSize()) {
    RouteLazyScreen(route, "Search materials", "${rows.size} files across ${pages.size} course${if (pages.size == 1) "" else "s"}" +
        (loading.size.takeIf { it > 0 }?.let { " · fetching $it more" } ?: "")) {
        item(key = "field") {
            Column {
                SearchField(query, { query = it }, onDone = {
                    repo.materials.remember(query)
                    keyboard?.hide()
                })
                Spacer(Modifier.height(10.dp))
                // Your faculty and Saved are one tap away; the rest lives behind the button.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterButton(listOfNotNull(subject, faculty, module).size) {
                        keyboard?.hide()
                        focus.clearFocus()
                        filtersOpen = true
                        haptics.tick()
                    }
                    Chip("Your faculty", mineOnly) { mineOnly = !mineOnly; haptics.tick() }
                    Chip("Saved", savedOnly) { savedOnly = !savedOnly; haptics.tick() }
                }
                if (subject != null || faculty != null || module != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            subject?.let { ActiveFilter(it) { subject = null; module = null; haptics.tick() } }
                            module?.let { ActiveFilter("Module $it") { module = null; haptics.tick() } }
                            faculty?.let { ActiveFilter(titleCase(it)) { faculty = null; haptics.tick() } }
                        }
                        Box(Modifier.clickable(role = Role.Button) {
                            subject = null; faculty = null; module = null; mineOnly = false; savedOnly = false
                            haptics.tick()
                        }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) { InkText("Clear all", style = RisoType.small) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (query.isBlank() && !filtered) {
            item(key = "recent") {
                Column {
                    if (recent.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GroupLabel("Recent", Modifier.weight(1f))
                            Box(Modifier.clickable(role = Role.Button) { repo.materials.forgetRecent() }.padding(top = 12.dp)) {
                                InkText("Clear", style = RisoType.small)
                            }
                        }
                        ChoiceRow(recent, -1) { query = recent[it] }
                    }
                    Spacer(Modifier.height(16.dp))
                    InkText(
                        if (rows.isEmpty()) "Fetching your course pages. Search works offline once they're here."
                        else "Try a topic, \"experiment 9\", a module, a faculty name or a course code. Typos are fine.",
                        style = RisoType.small,
                    )
                }
            }
            return@RouteLazyScreen
        }
        if (index == null) item(key = "indexing") { Loading("Getting the index ready") }
        else if (results.total == 0) item(key = "nothing") { InkText("Nothing matches. Check the filters, or try fewer words.", modifier = Modifier.padding(top = 12.dp)) }
        else item(key = "count") { InkText("${results.total} result${if (results.total == 1) "" else "s"}" + if (results.total > hits.size) " · best ${hits.size} shown" else "", label = true, modifier = Modifier.padding(top = 10.dp)) }
        hits.forEachIndexed { i, h ->
            item(key = "hit-${h.item.course.key}-${h.item.material.fileId}") {
                val r = h.item
                Column(Modifier.printIn(i.coerceAtMost(8))) {
                    FileRow(
                        r.key, r.material.title,
                        null,
                        fetch = { c, out, p -> c.downloadMaterial(r.material.fileId, out, p) },
                        highlighted = {
                            BasicText(
                                highlight(r.material.title, h.ranges[MaterialsStore.TITLE].orEmpty(), inks.a),
                                style = RisoType.body.copy(fontWeight = FontWeight.SemiBold, color = inks.text),
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                            // The indexed strings, cut down for the line but kept in step with the match ranges.
                            val moduleRanges = h.ranges[MaterialsStore.MODULE].orEmpty()
                            val moduleFull = listOf("Module ${r.material.module}", r.moduleTitle).filter { it.isNotBlank() && it != "Module " }.joinToString(" ")
                            val module = if (moduleRanges.isNotEmpty() || r.material.module.isEmpty()) moduleFull else "Module ${r.material.module}"
                            val course = "${r.course.code} ${r.course.name}"
                            val line = buildAnnotatedString {
                                append(highlight(course, h.ranges[MaterialsStore.COURSE].orEmpty().filter { it.first < course.length }, inks.a))
                                if (module.isNotBlank()) { append(" · "); append(highlight(module, moduleRanges, inks.a)) }
                                if (r.material.uploader.isNotBlank()) { append(" · "); append(highlight(nameCase(r.material.uploader), h.ranges[MaterialsStore.FACULTY].orEmpty(), inks.a)) }
                                if (r.material.kind.isNotBlank()) append(" · " + r.material.kind)
                                if (r.mine) append(" · your faculty")
                            }
                            BasicText(line, style = RisoType.small.copy(color = inks.text), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                    )
                    Hairline()
                }
            }
        }
    }
    LaunchedEffect(hits.isNotEmpty() && query.isNotBlank()) {
        // A search that found something is worth remembering, even if Done wasn't pressed.
        if (hits.isNotEmpty() && query.isNotBlank()) {
            kotlinx.coroutines.delay(1500)
            repo.materials.remember(query)
        }
    }
    FilterSheet(
        filtersOpen, results, filter,
        label = when {
            query.isBlank() && !filtered -> "Done"
            results.total == 0 -> "Close"
            else -> "Show ${results.total} result${if (results.total == 1) "" else "s"}"
        },
        onSubject = { subject = if (subject == it) null else it; module = null; haptics.tick() },
        onModule = { module = if (module == it) null else it; haptics.tick() },
        onFaculty = { faculty = if (faculty == it) null else it; haptics.tick() },
        onClear = { subject = null; faculty = null; module = null; haptics.tick() },
        onClose = { filtersOpen = false },
    )
    }
}

/** Slides up over the results, with its own scroll: a big course page can have dozens of faculty. */
@Composable
private fun FilterSheet(
    open: Boolean,
    results: MaterialResults,
    filter: MaterialFilter,
    label: String,
    onSubject: (String) -> Unit,
    onModule: (String) -> Unit,
    onFaculty: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val inks = LocalInks.current
    val ms = if (rememberMotionEnabled()) 220 else 0
    if (open) BackHandler(onBack = onClose)
    AnimatedVisibility(open, enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
                remember { MutableInteractionSource() }, indication = null, onClick = onClose,
            ),
        )
    }
    AnimatedVisibility(open, enter = slideInVertically(tween(ms)) { it }, exit = slideOutVertically(tween(ms)) { it }) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val shape = sheetShape(inks)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.86f)
                    .clip(shape)
                    .background(inks.paper)
                    .border(
                        if (inks.soft || inks.editorial || inks.glass) 1.dp else 2.dp,
                        if (inks.soft) inks.text.copy(alpha = 0.15f) else if (inks.editorial || inks.glass) inks.rule else inks.text,
                        shape,
                    )
                    // Taps on the sheet's own gaps mustn't reach the scrim under it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Heading("Filter", 24.sp) }
                    if (filter.subject != null || filter.faculty != null || filter.module != null) {
                        Box(Modifier.clickable(role = Role.Button, onClick = onClear).padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) { InkText("Clear", style = RisoType.small) }
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false).padding(bottom = 12.dp)) {
                    if (results.subjects.isEmpty() && results.faculties.isEmpty()) {
                        item(key = "none") {
                            InkText("Nothing to filter by. Your course pages may still be coming, or the search left no files.", style = RisoType.small, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                    if (results.subjects.isNotEmpty()) {
                        item(key = "subject-head") { GroupLabel("Subject code") }
                        results.subjects.forEach { o ->
                            item(key = "subject-${o.id}") { OptionRow(o.id, o.detail, o.count, o.id == filter.subject) { onSubject(o.id) } }
                        }
                    }
                    // A module only means something inside one course.
                    if (filter.subject != null && results.modules.isNotEmpty()) {
                        item(key = "module") {
                            Column {
                                GroupLabel("Module")
                                ModuleChips(results.modules, filter.module, onModule)
                            }
                        }
                    }
                    if (results.faculties.isNotEmpty()) {
                        item(key = "faculty-head") { GroupLabel("Faculty") }
                        results.faculties.forEach { o ->
                            item(key = "faculty-${o.id}") { OptionRow(titleCase(o.id), if (o.mine) "Your faculty" else "", o.count, o.id == filter.faculty) { onFaculty(o.id) } }
                        }
                    }
                }
                InkButton(label, onClose, fillWidth = true)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleChips(options: List<FilterOption>, selected: String?, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Chip("Module ${o.id}", o.id == selected) { onPick(o.id) } }
    }
}

/** One value in the sheet: picked, it reverses out just as a solid chip does. */
@Composable
private fun OptionRow(title: String, detail: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val inks = LocalInks.current
    val ink = if (on && !inks.sticker) inks.paper else inks.text
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(chipShape(inks))
            .background(if (on) solidInk(inks) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold, color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) BasicText(detail, style = RisoType.small.copy(color = if (on) ink else ink.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        BasicText("$count", style = RisoType.small.copy(color = ink))
    }
}

/** Opens the sheet. Solid, with how many are set, while any filter in it is on. */
@Composable
private fun FilterButton(active: Int, onClick: () -> Unit) {
    val inks = LocalInks.current
    val shape = chipShape(inks)
    val on = active > 0
    val ink = if (on && !inks.sticker) inks.paper else inks.text
    Row(
        Modifier
            .clip(shape)
            .background(if (on) solidInk(inks) else Color.Transparent)
            .border(if (inks.soft || inks.editorial) 1.dp else 1.5.dp, if (on) solidInk(inks) else if (inks.glass) inks.rule else inks.text, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (on) "Filter, $active set" else "Filter" }
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterGlyph(ink)
        Spacer(Modifier.width(8.dp))
        BasicText(if (on) "Filter · $active" else "Filter", style = RisoType.label.copy(color = ink, letterSpacing = RisoType.label.letterSpacing * 0.5f))
    }
}

/** A set filter: tapping it takes it off. */
@Composable
private fun ActiveFilter(text: String, onRemove: () -> Unit) {
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove filter $text" }
            .padding(vertical = 4.dp),
    ) { InkChip("$text  ×", solid = true) }
}

@Composable
private fun FilterGlyph(ink: Color) {
    androidx.compose.foundation.Canvas(Modifier.width(16.dp).height(16.dp)) {
        val w = 2.dp.toPx()
        listOf(0.2f to 1f, 0.5f to 0.64f, 0.8f to 0.28f).forEach { (y, across) ->
            val inset = size.width * (1 - across) / 2
            drawLine(ink, androidx.compose.ui.geometry.Offset(inset, size.height * y), androidx.compose.ui.geometry.Offset(size.width - inset, size.height * y), w, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** The same shape and ink InkChip takes in each style, for the controls that sit beside chips. */
private fun chipShape(inks: Inks): Shape = if (inks.soft) RoundedCornerShape(50) else if (inks.editorial) EditorialShape else RoundedCornerShape(6.dp)

private fun solidInk(inks: Inks): Color = if (inks.soft || inks.editorial) inks.text else if (inks.sticker || inks.glass) inks.a else inks.b

private fun sheetShape(inks: Inks): Shape = when {
    inks.editorial -> EditorialShape
    inks.soft -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    inks.glass -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    else -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, onDone: () -> Unit, autoFocus: Boolean = true) {
    val inks = LocalInks.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .clip(CardShape)
            .background(inks.paper)
            .border(3.dp, inks.a, CardShape)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph()
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = RisoType.body.copy(fontSize = 18.sp, color = inks.text),
            cursorBrush = SolidColor(inks.a),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onDone() }),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(focus),
        )
        if (value.isNotEmpty()) {
            Box(Modifier.clickable(role = Role.Button) { onChange("") }.padding(10.dp)) { InkText("Clear", style = RisoType.small) }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Checkbox, onClick = onClick).padding(vertical = 4.dp)) { InkChip(text, solid = on) }
}

/** [text] with [ranges] marked in [ink] (as a translucent plate, so the text reads through it). */
internal fun highlight(text: String, ranges: List<IntRange>, ink: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { r ->
        val start = r.first.coerceIn(0, text.length)
        val end = (r.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(SpanStyle(background = ink.copy(alpha = 0.32f), fontWeight = FontWeight.SemiBold), start, end)
    }
}

