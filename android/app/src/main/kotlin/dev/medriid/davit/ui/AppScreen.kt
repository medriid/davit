package dev.medriid.davit.ui

import dev.medriid.davit.design.LocalInks
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import android.Manifest
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import dev.medriid.davit.sync.Notifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Repo
import dev.medriid.davit.data.UiState
import dev.medriid.davit.design.Grain
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.NavItem
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoNavBar
import dev.medriid.davit.design.RisoTheme
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Seal
import kotlinx.coroutines.delay

internal val Gutter = 18.dp

private val Tabs = listOf(
    NavItem("Today", RisoIcon.Today),
    NavItem("Attendance", RisoIcon.Attendance),
    NavItem("Academics", RisoIcon.Academics),
    NavItem("Week", RisoIcon.Week),
)

/** Root: the sign-in poster until there are credentials, then the four tabs. */
@Composable
fun AppScreen(repo: Repo, locked: Boolean = false, onUnlock: () -> Unit = {}) {
    val state by repo.state.collectAsState()
    val dark = when (state.theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    // Bar icons follow the paper: dark icons on paper, light ones at night.
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
    RisoTheme(InkPairs.of(state.inkIndex, dark)) {
        Grain(Modifier.fillMaxSize()) {
            if (locked && state.signedIn) {
                LockScreen(onUnlock)
                return@Grain
            }
            Crossfade(state.signedIn, label = "signedIn") { signedIn ->
                if (signedIn) Main(state, repo) else SignInScreen(state, repo::signIn)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(state: UiState, repo: Repo) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val mascot = Mascot.parse(state.mascot)

    // Ask for notification permission once, after the first data is on screen.
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.snapshot != null) {
        if (state.snapshot != null && state.alerts && Build.VERSION.SDK_INT >= 33 &&
            !repo.askedForNotifications && !Notifier.permitted(context)
        ) {
            repo.askedForNotifications = true
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = sheet || tab != 0) {
        if (sheet) sheet = false else tab = 0
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, mascot, onSync = repo::sync, onProfile = { sheet = true }, Modifier.windowInsetsPadding(WindowInsets.statusBars))
            AnimatedVisibility(state.error != null) {
                ErrorBanner(state.error.orEmpty(), onRetry = repo::sync, onDismiss = repo::dismissError)
            }
            val pull = rememberPullToRefreshState()
            val inks = LocalInks.current
            PullToRefreshBox(
                isRefreshing = state.busy != null,
                onRefresh = repo::sync,
                modifier = Modifier.weight(1f),
                state = pull,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pull,
                        isRefreshing = state.busy != null,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = inks.paper,
                        color = inks.a,
                    )
                },
            ) {
                val snapshot = state.snapshot
                if (snapshot == null) {
                    FirstSync(state.busy)
                } else {
                    Crossfade(tab, label = "tab") { t ->
                        when (t) {
                            0 -> TodayTab(
                                snapshot, state.reminders,
                                onOpenAttendance = { tab = 1 }, onOpenWeek = { tab = 3 }, onOpenAcademics = { tab = 2 },
                                onAddReminder = repo::addReminder, onRemoveReminder = repo::removeReminder,
                            )
                            1 -> AttendanceTab(snapshot)
                            2 -> AcademicsTab(snapshot)
                            else -> WeekTab(snapshot)
                        }
                    }
                }
            }
            RisoNavBar(Tabs, tab, { tab = it }, Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        AccountSheet(
            visible = sheet,
            state = state,
            mascot = mascot,
            onMascot = { repo.setMascot(it.encode()) },
            onInk = repo::setInk,
            onTheme = repo::setTheme,
            onAppLock = repo::setAppLock,
            onSemester = repo::chooseSemester,
            onSync = { repo.sync(); sheet = false },
            onDumpPages = repo::setDumpPages,
            onBackground = repo::setBackground,
            onAlerts = repo::setAlerts,
            onSignOut = { sheet = false; repo.signOut() },
            onClose = { sheet = false },
        )
    }
}

@Composable
private fun TopBar(state: UiState, mascot: Mascot, onSync: () -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seal(size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Heading("daVIT", 26.sp, uppercase = false)
        Spacer(Modifier.width(10.dp))
        // The status takes whatever room is left (and clips), so a long "Fetching
        // attendance" can never squeeze the avatar out of round.
        Column(
            Modifier
                .weight(1f)
                .clickable(enabled = state.busy == null, role = Role.Button, onClick = onSync)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (state.busy != null) {
                InkText("Syncing", label = true)
                InkText(state.busy, style = RisoType.small.copy(textAlign = TextAlign.End), maxLines = 1)
            } else {
                InkText("Synced", label = true)
                InkText(state.snapshot?.syncedAt?.let { ago(it) } ?: "never", style = RisoType.small)
            }
        }
        Spacer(Modifier.width(8.dp))
        MascotAvatar(
            mascot, 48.dp,
            Modifier
                .clickable(role = Role.Button, onClick = onProfile)
                .semantics { contentDescription = "Profile and settings" },
        )
    }
}

/** "just now", "12 min ago", "3 h ago", "2 days ago"; ticks over once a minute. */
@Composable
internal fun ago(at: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val min = (now - at) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    // IntrinsicSize.Min: InkCard fills the height it's offered, and here that's the screen.
    InkCard(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp).height(IntrinsicSize.Min), plateOffset = 4.dp) {
        InkText(text)
        Spacer(Modifier.height(10.dp))
        Row {
            InkButton("Retry", onRetry, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
            Spacer(Modifier.width(12.dp))
            InkButton("Dismiss", onDismiss, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
        }
    }
}

/** Signed in, nothing cached yet: the first sync's progress, big. */
@Composable
private fun FirstSync(busy: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Seal(size = 72.dp)
        Spacer(Modifier.height(22.dp))
        Heading("Printing", 30.sp)
        Spacer(Modifier.height(10.dp))
        InkText(busy ?: "Waiting to sync. Tap \"Synced\" up top to try again.", style = RisoType.body.copy(textAlign = TextAlign.Center))
    }
}

/** Scrollable tab body with the standard gutters. */
@Composable
internal fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Heading(text, 22.sp, modifier.padding(top = 26.dp, bottom = 12.dp))
}

/** Shown while the app lock is on and you haven't unlocked yet. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Seal(size = 72.dp)
        Spacer(Modifier.height(22.dp))
        Heading("Locked", 30.sp)
        Spacer(Modifier.height(18.dp))
        InkButton("Unlock", onUnlock, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}
