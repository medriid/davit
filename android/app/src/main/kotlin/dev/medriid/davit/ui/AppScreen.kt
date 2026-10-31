package dev.medriid.davit.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.medriid.davit.data.bannerText
import dev.medriid.davit.data.BannerStat
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.LocalInks
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.medriid.davit.design.DrumIndicator
import dev.medriid.davit.design.Overprint
import dev.medriid.davit.design.PrintingSheet
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.rememberMotionEnabled
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
    NavItem("Home", RisoIcon.Today),
    NavItem("Attendance", RisoIcon.Attendance),
    NavItem("Academics", RisoIcon.Academics),
    NavItem("Week", RisoIcon.Week),
    NavItem("Navigation", RisoIcon.Map),
)

/** Root: the sign-in poster until there are credentials, then the five tabs. */
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
    val inks = state.palette.inks(dark)
    // Where you are (tab and open screens) lives outside the rebuild below, so changing style keeps you there.
    val tab = rememberSaveable { mutableIntStateOf(0) }
    val stack = rememberSaveable(saver = Route.StackSaver) { mutableStateListOf<Route>() }
    LaunchedEffect(state.signedIn) {
        if (!state.signedIn) {
            stack.clear()
            tab.intValue = 0
        }
    }
    RisoTheme(inks) {
        // Switching style rebuilds everything, so every text picks up its type.
        key(inks.soft, inks.editorial) {
        Grain(Modifier.fillMaxSize()) {
            if (locked && state.signedIn) {
                LockScreen(onUnlock)
                return@Grain
            }
            // Signed out: the welcome (look and mascot) the first time, then the sign-in form.
            var welcome by rememberSaveable { mutableStateOf(!repo.welcomed) }
            Crossfade(if (state.signedIn) 0 else if (welcome) 1 else 2, label = "signedIn") { screen ->
                when (screen) {
                    0 -> Main(state, repo, tab, stack)
                    1 -> WelcomeScreen(state, repo) {
                        repo.welcomed = true
                        welcome = false
                    }
                    else -> SignInScreen(state, repo::signIn, onLook = { welcome = true })
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Main(state: UiState, repo: Repo, tabState: MutableIntState, stack: SnapshotStateList<Route>) {
    var tab by tabState
    val nav = remember(stack) { Nav(stack) { tab = it } }
    val mascot = Mascot.parse(state.mascot)
    val motion = rememberMotionEnabled()
    val haptics = rememberHaptics()
    // Each tab and the tab host keep their scroll and segment state while a screen is on top.
    val holder = rememberSaveableStateHolder()

    // Once, after the first data is on screen: the setup (notifications, background, reminders, semester).
    var setup by rememberSaveable { mutableStateOf(!repo.setupDone) }

    BackHandler(enabled = stack.isNotEmpty() || tab != 0) {
        when {
            stack.isNotEmpty() -> nav.back()
            else -> tab = 0
        }
    }

    CompositionLocalProvider(LocalNav provides nav, LocalRepo provides repo) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // The display card on Home only; every other tab and screen gets the plain bar.
                // Soft Home draws its own hello in place of the bar.
                if ((LocalInks.current.soft || LocalInks.current.editorial) && tab == 0 && stack.isEmpty()) Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))
                else TopBar(state, mascot, repo.bannerPhoto, card = tab == 0 && stack.isEmpty(), onSync = repo::sync, onProfile = { nav.go(Route.Settings) }, onMore = { nav.go(Route.Campus) }, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
                AnimatedVisibility(state.error != null) {
                    ErrorBanner(state.error.orEmpty(), onRetry = repo::sync, onDismiss = repo::dismissError)
                }
                Box(Modifier.weight(1f)) {
                    val snapshot = state.snapshot
                    if (snapshot == null) {
                        FirstSync(state.busy)
                    } else {
                        SharedTransitionLayout {
                            AnimatedContent(
                                targetState = stack.lastOrNull(),
                                contentKey = { it?.id ?: "tabs" },
                                transitionSpec = {
                                    if (!motion) EnterTransition.None togetherWith ExitTransition.None
                                    else fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(90))
                                },
                                label = "route",
                            ) { route ->
                                CompositionLocalProvider(LocalShared provides this@SharedTransitionLayout, LocalAnimScope provides this) {
                                    if (route == null) {
                                        holder.SaveableStateProvider("tabs") { TabHost(tab, state, snapshot, repo, motion, holder) { tab = it } }
                                    } else {
                                        holder.SaveableStateProvider(route.id) { RouteHost(route) }
                                    }
                                }
                            }
                        }
                    }
                }
                RisoNavBar(Tabs, tab, {
                    if (it != tab || stack.isNotEmpty()) haptics.tick()
                    nav.tab(it)
                }, Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
            // The scanner covers everything, bars included, while it's open.
            ScannerHost()
            if (setup && state.snapshot != null) {
                BackHandler { }
                SetupScreen(state, repo) {
                    repo.setupDone = true
                    setup = false
                }
            }
        }
    }
}

/** The five tabs under one pull-to-refresh (the drum; the map keeps its drags to itself), switching with a short slide. */
@Composable
private fun TabHost(
    tab: Int,
    state: UiState,
    snapshot: dev.medriid.davit.data.Snapshot,
    repo: Repo,
    motion: Boolean,
    holder: androidx.compose.runtime.saveable.SaveableStateHolder,
    onTab: (Int) -> Unit,
) {
    // Only a sync you pulled for shows the drum; others say so in the top bar.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) { if (state.busy == null) pulled = false }
    val nav = LocalNav.current
    PullBox(pulled && state.busy != null, onRefresh = {
        pulled = true
        repo.sync()
    }, Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (!motion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spring(dampingRatio = 0.82f, stiffness = 420f)) { dir * it / 9 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(170)) { -dir * it / 14 } + fadeOut(tween(130)))
                }
            },
            label = "tab",
        ) { t ->
            holder.SaveableStateProvider("tab$t") {
                when (t) {
                    0 -> TodayTab(
                        snapshot, state.reminders,
                        onOpenAttendance = { onTab(1) }, onOpenWeek = { onTab(3) }, onOpenAcademics = { onTab(2) },
                        onAddReminder = repo::addReminder, onRemoveReminder = repo::removeReminder,
                        onOpen = nav::go,
                    )
                    1 -> AttendanceTab(snapshot)
                    2 -> AcademicsTab(snapshot)
                    3 -> WeekTab(snapshot)
                    else -> dev.medriid.davit.ui.map.MapTab(snapshot)
                }
            }
        }
    }
}

@Composable
private fun RouteHost(route: Route) = when (route) {
    is Route.Assignment -> AssignmentScreen(route)
    is Route.Course -> CourseScreen(route)
    Route.Search -> SearchScreen(route)
    Route.Campus -> CampusHub(route)
    Route.Messages -> MessagesScreen(route)
    Route.Spotlight -> SpotlightScreen(route)
    Route.Events -> EventsScreen(route)
    Route.People -> PeopleScreen(route)
    Route.Money -> MoneyScreen(route)
    Route.Gate -> GateScreen(route)
    Route.Curriculum -> CurriculumScreen(route)
    Route.SemesterGrades -> SemesterGradesScreen(route)
    Route.Hostel -> HostelScreen(route)
    Route.Downloads -> DownloadsScreen(route)
    Route.Settings -> SettingsScreen(route)
    Route.Notifications -> NotificationsScreen(route)
    Route.Bonafide -> BonafideScreen(route)
    Route.CourseFeedback -> CourseFeedbackScreen(route)
    Route.PaperSeeing -> PaperSeeingScreen(route)
    Route.Activities -> ActivitiesScreen(route)
    Route.Allocation -> AllocationScreen(route)
    Route.Colours -> ColoursScreen(route)
    Route.LeavePlanner -> LeavePlannerScreen(route)
    Route.DisplayCard -> DisplayCardScreen(route)
    Route.CourseNames -> CourseNamesScreen(route)
    Route.Corrections -> CorrectionsScreen(route)
    Route.ExamBoard -> ExamBoardScreen(route)
    Route.Friends -> FriendsScreen(route)
    Route.Planner -> PlannerScreen(route)
}

@Composable
internal fun TopBar(state: UiState, mascot: Mascot, photo: java.io.File, card: Boolean, onSync: () -> Unit, onProfile: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier, gutter: Dp = Gutter) {
    if (card && !state.banner.hidden) {
        CardBar(state, mascot, photo, onSync, onProfile, onMore, modifier, gutter)
        return
    }
    Row(
        modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seal(size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Heading("daVIT", 24.sp, uppercase = false)
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
        // More from VTOP, a tap away from anywhere.
        TopIcon(RisoIcon.More, "More from VTOP", onMore)
        Spacer(Modifier.width(6.dp))
        // You and your settings: the mascot, with a gear on it so it reads as a button.
        Box(
            Modifier
                .clickable(role = Role.Button, onClick = onProfile)
                .semantics { contentDescription = "Profile and settings" },
        ) {
            MascotAvatar(mascot, 46.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .background(LocalInks.current.paper, CircleShape)
                    .border(1.5.dp, LocalInks.current.text, CircleShape)
                    .padding(2.dp),
            ) { IconGlyph(RisoIcon.Settings, LocalInks.current.text, Modifier.fillMaxSize()) }
        }
    }
}

/**
 * The top bar as your display card: the logo, More and your mascot on your background (a
 * built-in print or your photo), and under them the two stats you picked and the sync
 * status. Everything that sits on the picture gets a paper pill, so it reads on any photo.
 */
@Composable
private fun CardBar(state: UiState, mascot: Mascot, photo: java.io.File, onSync: () -> Unit, onProfile: () -> Unit, onMore: () -> Unit, modifier: Modifier, gutter: Dp) {
    val inks = LocalInks.current
    val banner = state.banner
    val shape = RoundedCornerShape(16.dp)
    val now = remember { java.time.LocalDateTime.now() }
    val left = state.snapshot?.bannerText(BannerStat.of(banner.left), state.reminders, now)
    val right = state.snapshot?.bannerText(BannerStat.of(banner.right), state.reminders, now)
    Box(modifier.fillMaxWidth().padding(start = gutter, end = (gutter - 4.dp).coerceAtLeast(0.dp), top = 6.dp, bottom = 8.dp)) {
        Box(Modifier.padding(end = 4.dp, bottom = 4.dp)) {
            Box(Modifier.matchParentSize().offset(4.dp, 4.dp).clip(shape).background(inks.plate(null)))
            Box(Modifier.fillMaxWidth().clip(shape).border(2.dp, inks.text, shape)) {
                BannerBackground(banner, state.bannerPhotoAt, photo, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Seal(size = 34.dp)
                        Spacer(Modifier.width(8.dp))
                        // The name pill doubles as the sync button: tap it to sync; under the name, when it last did (or what it's doing).
                        Pill(Modifier.widthIn(max = 170.dp).clickable(enabled = state.busy == null, role = Role.Button, onClick = onSync)) {
                            Column {
                                val name = state.snapshot?.profile?.name?.ifBlank { null }?.takeIf { banner.nameInBar }
                                if (name != null) InkText(name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                else Heading("daVIT", 18.sp, uppercase = false)
                                InkText(
                                    state.busy ?: ("Synced " + (state.snapshot?.syncedAt?.let { ago(it) } ?: "never")),
                                    style = RisoType.small.copy(fontSize = 11.sp), maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.clip(CircleShape).background(inks.paper).border(1.5.dp, inks.text, CircleShape)) {
                            TopIcon(RisoIcon.More, "More from VTOP", onMore)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clickable(role = Role.Button, onClick = onProfile)
                                .semantics { contentDescription = "Profile and settings" },
                        ) {
                            MascotAvatar(mascot, 44.dp)
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(17.dp)
                                    .background(inks.paper, CircleShape)
                                    .border(1.5.dp, inks.text, CircleShape)
                                    .padding(2.dp),
                            ) { IconGlyph(RisoIcon.Settings, inks.text, Modifier.fillMaxSize()) }
                        }
                    }
                    if (left != null || right != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            left?.let { StatChip(it) }
                            right?.let { StatChip(it) }
                        }
                    }
                }
            }
        }
    }
}

/** Paper behind text that sits on a picture, so it reads on anything. */
@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val inks = LocalInks.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(inks.paper).border(1.5.dp, inks.text, shape).padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TopIcon(icon: RisoIcon, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon, LocalInks.current.text, Modifier.size(24.dp)) }
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

/** The first sync's steps, as the stages of a print run. */
private val FirstStages = listOf("Connecting to VTOP", "Solving the captcha", "Signing in", "Reading semesters", "Fetching attendance")

private fun stageOf(busy: String?): Int {
    val b = busy?.lowercase() ?: return 0
    return when {
        "attendance" in b -> 4
        "semester" in b -> 3
        "signing in" in b -> 2
        "captcha" in b -> 1
        else -> 0
    }
}

/** Signed in, nothing cached yet: the first sync prints, stage by stage, while a roller runs. */
@Composable
private fun FirstSync(busy: String?) {
    var reached by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy) { reached = maxOf(reached, stageOf(busy)) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Seal(size = 72.dp)
        Spacer(Modifier.height(18.dp))
        DrumIndicator(1f, refreshing = busy != null, modifier = Modifier.width(220.dp))
        Overprint("Printing", 34.sp, registerKey = reached)
        Spacer(Modifier.height(22.dp))
        if (busy == null) {
            InkText("Waiting to sync. Tap \"Synced\" up top to try again.", style = RisoType.body.copy(textAlign = TextAlign.Center))
        } else {
            PrintingSheet(FirstStages, reached, Modifier.width(280.dp))
            Spacer(Modifier.height(16.dp))
            InkText(busy, style = RisoType.small.copy(textAlign = TextAlign.Center))
        }
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
