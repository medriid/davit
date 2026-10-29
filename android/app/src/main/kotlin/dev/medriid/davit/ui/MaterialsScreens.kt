package dev.medriid.davit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
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
import dev.medriid.davit.data.MaterialRow
import dev.medriid.davit.data.MaterialsStore
import dev.medriid.davit.data.Section
import dev.medriid.davit.data.Snapshot
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
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
                CourseCard(c, cached, rows.count { it.mine }, rows.count { it.key in saved }, Modifier.printIn(i++)) { nav.go(Route.Course(c.key)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    InkText("Pages open offline once fetched. Downloads stay on this phone until you delete them.", style = RisoType.small.copy(color = LocalInks.current.rule))
}

@Composable
private fun CourseCard(c: CourseOption, cached: CachedCourse?, mine: Int, saved: Int, modifier: Modifier, onOpen: () -> Unit) {
    InkCard(modifier.fillMaxWidth().sharedCard(Route.Course(c.key).sharedKey), onClick = onOpen, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(c.name.ifBlank { c.code }, style = RisoType.title, maxLines = 2)
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
        route, course.code, "${course.name} · ${course.typeName.ifBlank { course.type }}",
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
 * course, with filter chips and highlighted matches. The index and every search run on a
 * background thread, so typing never waits on it.
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
    var course by rememberSaveable { mutableStateOf<String?>(null) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var savedOnly by rememberSaveable { mutableStateOf(false) }
    var module by rememberSaveable { mutableStateOf<String?>(null) }

    val rows = remember(pages, snapshot) { repo.materials.allRows(snapshot) }
    val index by produceState<Fuzzy.Index<MaterialRow>?>(null, rows) {
        value = withContext(Dispatchers.Default) { repo.materials.index(rows) }
    }
    val filtered = course != null || mineOnly || savedOnly || module != null
    val hits by produceState(emptyList<Fuzzy.Hit<MaterialRow>>(), index, query, course, mineOnly, savedOnly, module, saved) {
        val idx = index ?: return@produceState
        value = if (query.isBlank() && !filtered) emptyList() else withContext(Dispatchers.Default) {
            idx.search(query, limit = 150) { r ->
                (course == null || r.course.key == course) && (!mineOnly || r.mine) && (!savedOnly || r.key in saved) &&
                    (module == null || r.material.module == module)
            }
        }
    }
    val modules = remember(rows, course) {
        rows.filter { course == null || it.course.key == course }.map { it.material.module }.filter { it.isNotBlank() }.distinct()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = rememberHaptics()

    RouteLazyScreen(route, "Search materials", "${rows.size} files across ${pages.size} course${if (pages.size == 1) "" else "s"}" +
        (loading.size.takeIf { it > 0 }?.let { " · fetching $it more" } ?: "")) {
        item(key = "field") {
            Column {
                SearchField(query, { query = it }, onDone = {
                    repo.materials.remember(query)
                    keyboard?.hide()
                })
                Spacer(Modifier.height(10.dp))
                // Course chips scroll sideways: there can be a dozen.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Your faculty", mineOnly) { mineOnly = !mineOnly; haptics.tick() }
                    Chip("Saved", savedOnly) { savedOnly = !savedOnly; haptics.tick() }
                    campus.courses.filter { pages.containsKey(it.key) }.forEach { c ->
                        Chip(c.code + if (dev.medriid.davit.data.isLabCode(c.type)) " lab" else "", course == c.key) {
                            course = if (course == c.key) null else c.key
                            module = null
                            haptics.tick()
                        }
                    }
                }
                if (modules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modules.forEach { m -> Chip("Module $m", module == m) { module = if (module == m) null else m; haptics.tick() } }
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
        else if (hits.isEmpty()) item(key = "nothing") { InkText("Nothing matches. Check the filters, or try fewer words.", modifier = Modifier.padding(top = 12.dp)) }
        else item(key = "count") { InkText("${hits.size}${if (hits.size == 150) "+" else ""} result${if (hits.size == 1) "" else "s"}", label = true, modifier = Modifier.padding(top = 10.dp)) }
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
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val inks = LocalInks.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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

