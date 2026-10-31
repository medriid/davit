package dev.medriid.davit.ui

import dev.medriid.davit.design.EditorialShape
import dev.medriid.davit.design.Rule
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Repo
import dev.medriid.davit.data.sizeText
import dev.medriid.davit.design.DrumIndicator
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LoadingPlate
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.vtop.Download
import dev.medriid.davit.vtop.VtopClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

val LocalRepo = staticCompositionLocalOf<Repo> { error("no Repo") }

/**
 * A screen above the tabs: back + title, pull to refresh (the drum) when [onRefresh] is
 * given, and the container transform from whatever card opened it ([Route.sharedKey]).
 */
@Composable
internal fun RouteScreen(
    route: Route,
    title: String,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inks = LocalInks.current
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize().sharedCard(route.sharedKey).background(inks.paper)) {
        PullBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter)
                    .padding(top = 4.dp, bottom = 28.dp),
            ) {
                RouteHead(title, subtitle)
                content()
            }
        }
    }
}

/** [RouteScreen] for long lists (a course page can hold hundreds of files): only what's on screen is composed. */
@Composable
internal fun RouteLazyScreen(
    route: Route,
    title: String,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val inks = LocalInks.current
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize().sharedCard(route.sharedKey).background(inks.paper)) {
        PullBox(refreshing, onRefresh, Modifier.fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 28.dp),
            ) {
                item(key = "head") { RouteHead(title, subtitle) }
                content()
            }
        }
    }
}

/** The title block of a route screen: back, title and subtitle; in Editorial a big headline over a firm rule. */
@Composable
private fun RouteHead(title: String, subtitle: String?) {
    val inks = LocalInks.current
    val nav = LocalNav.current
    if (inks.editorial) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
            BackButton { nav.back() }
            Spacer(Modifier.height(18.dp))
            BasicText(title, style = RisoType.display(36.sp).copy(color = inks.text))
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                BasicText(subtitle, style = RisoType.small.copy(color = inks.muted, fontSize = 14.sp), maxLines = 3)
            }
            Spacer(Modifier.height(14.dp))
            Rule()
        }
        return
    }
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton { nav.back() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Heading(title, 24.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                InkText(subtitle, style = RisoType.small, maxLines = 2)
            }
        }
    }
}

/** A round back arrow, 48 dp to hit; square with a hairline in Editorial. */
@Composable
internal fun BackButton(onClick: () -> Unit) {
    val inks = LocalInks.current
    val shape = if (inks.editorial) EditorialShape else CircleShape
    Box(
        Modifier
            .size(44.dp)
            .border(
                if (inks.soft || inks.editorial) 1.dp else 2.dp,
                when {
                    inks.editorial -> inks.rule
                    inks.soft -> inks.text.copy(alpha = 0.25f)
                    else -> inks.text
                },
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = 2.4.dp.toPx()
            drawLine(inks.text, Offset(size.width * 0.62f, size.height * 0.18f), Offset(size.width * 0.3f, size.height * 0.5f), w, StrokeCap.Round)
            drawLine(inks.text, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.82f), w, StrokeCap.Round)
        }
    }
}

/** Pull to refresh with the riso drum; a plain box without [onRefresh]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullBox(refreshing: Boolean, onRefresh: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (onRefresh == null) {
        Box(modifier, content = content)
        return
    }
    val state = rememberPullToRefreshState()
    val haptics = rememberHaptics()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            haptics.heavy()
            onRefresh()
        },
        modifier = modifier,
        state = state,
        indicator = { DrumIndicator(state.distanceFraction, refreshing, Modifier.align(Alignment.TopCenter)) },
    ) {
        // The sheet slides down with the pull, so the drum rolls in above it, not over it.
        // Read in the layer, so pulling doesn't recompose the screen.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = state.distanceFraction.coerceIn(0f, 1.4f) * 52.dp.toPx()
            },
            content = content,
        )
    }
}

/** "Loading" in ink: halftone bars while a section arrives for the first time. */
@Composable
internal fun Loading(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        InkText(text, style = RisoType.small)
        Spacer(Modifier.height(12.dp))
        LoadingPlate(lines = 4)
    }
}

/** A failure under the data it couldn't refresh, with a retry. */
@Composable
internal fun ErrorNote(text: String, onRetry: (() -> Unit)?) {
    InkCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), plateOffset = 3.dp) {
        InkText(text, style = RisoType.small)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clickable(role = Role.Button, onClick = onRetry)) { InkChip("Try again", solid = true) }
        }
    }
}

/** "Updated 3 h ago · pull down to refresh". */
@Composable
internal fun Freshness(at: Long) {
    if (at <= 0L) return
    InkText("Updated ${ago(at)} · pull down to refresh", style = RisoType.small.copy(color = LocalInks.current.rule), modifier = Modifier.padding(bottom = 10.dp))
}

/**
 * Downloads [key] into app storage (in the app's scope, so leaving the screen doesn't stop
 * it), then calls [done] on the main thread.
 */
internal fun Repo.fetchFile(
    key: String,
    block: (VtopClient, OutputStream, (Long, Long?) -> Unit) -> Download,
    done: (Result<File>) -> Unit,
) {
    scope.launch {
        val r = runCatching { files.fetch(key) { tmp, progress -> onVtop { c -> tmp.outputStream().buffered().use { block(c, it, progress) } } } }
        withContext(Dispatchers.Main) { done(r) }
    }
}

/**
 * A file VTOP serves: tap to download and open (with progress), tap again to open it
 * offline; long-press, or the Delete chip, removes the copy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    key: String,
    title: String,
    subtitle: String?,
    fetch: (VtopClient, OutputStream, (Long, Long?) -> Unit) -> Download,
    modifier: Modifier = Modifier,
    highlighted: @Composable (() -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val haptics = rememberHaptics()
    val saved by repo.files.saved.collectAsState()
    val progress by repo.files.progress.collectAsState()
    val file = saved[key]?.takeIf { it.exists() }
    val p = progress[key]
    var error by remember(key) { mutableStateOf<String?>(null) }
    var askDelete by remember(key) { mutableStateOf(false) }
    val open = {
        error = null
        if (file != null) {
            if (!repo.files.open(file)) error = "No app on this phone opens .${file.extension} files."
        } else if (p == null) {
            repo.fetchFile(key, fetch) { r ->
                r.onSuccess {
                    haptics.confirm()
                    if (!repo.files.open(it)) error = "Saved, but no app on this phone opens .${it.extension} files."
                }.onFailure {
                    haptics.reject()
                    error = repo.explain(it)
                }
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(role = Role.Button, onClick = open, onLongClick = { if (file != null) askDelete = !askDelete })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (highlighted != null) highlighted() else InkText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
                val line = listOfNotNull(subtitle, file?.let { "saved · " + sizeText(it.length()) }).joinToString(" · ")
                if (line.isNotEmpty()) InkText(line, style = RisoType.small, maxLines = 2)
            }
            Spacer(Modifier.width(10.dp))
            when {
                p != null -> InkChip(p.fraction?.let { "${(it * 100).toInt()}%" } ?: sizeText(p.done), solid = true)
                file != null -> InkChip("Open", solid = true)
                else -> InkChip("Get")
            }
        }
        if (p != null) {
            dev.medriid.davit.design.HalftoneBar(p.fraction ?: 0.5f, height = 6.dp)
            Spacer(Modifier.height(6.dp))
        }
        AnimatedVisibility(askDelete && file != null) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clickable(role = Role.Button) { repo.files.delete(key); askDelete = false; haptics.tick() }) { InkChip("Delete download", solid = true) }
                Box(Modifier.clickable(role = Role.Button) { file?.let(repo.files::share); askDelete = false }) { InkChip("Share") }
                Box(Modifier.clickable(role = Role.Button) { askDelete = false }) { InkChip("Keep") }
            }
        }
        error?.let { InkText(it, style = RisoType.small.copy(color = inks.a), modifier = Modifier.padding(bottom = 6.dp)) }
    }
}

/** A label above a group of rows, like the tabs' section titles but smaller. */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    InkText(text, label = true, modifier = modifier.padding(top = 18.dp, bottom = 6.dp))
}

/** A 1 dp rule between rows of a long list; [Rule] is for sections. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(LocalInks.current.rule))
}
