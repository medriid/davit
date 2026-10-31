@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.medriid.davit.ui

import dev.medriid.davit.design.InkField
import androidx.compose.foundation.layout.FlowRow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.toArgb
import dev.medriid.davit.data.icsOf
import dev.medriid.davit.data.classEvents
import dev.medriid.davit.data.PhoneCalendar
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.AlertPrefs
import dev.medriid.davit.data.Updates
import dev.medriid.davit.data.courseKey
import dev.medriid.davit.data.percent
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.sync.AutoUpdate
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.Notifier
import dev.medriid.davit.widget.DavitWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The groups Settings opens onto, each its own short page. */
internal enum class SettingsGroup(val id: String, val title: String, val icon: RisoIcon) {
    Look("look", "Look", RisoIcon.Picture),
    Yours("yours", "Make it yours", RisoIcon.Profile),
    Alerts("alerts", "Notifications", RisoIcon.Bell),
    Sync("sync", "Sync and semester", RisoIcon.Today),
    Phone("phone", "On this phone", RisoIcon.Download),
    About("about", "About and help", RisoIcon.Book),
}

/** Every setting by name, for Find a setting: what it's called, other words for it, and its group. */
private val SettingsIndex: List<Triple<String, String, SettingsGroup>> = listOf(
    Triple("Style: Classic or Editorial", "ui theme look layout", SettingsGroup.Look),
    Triple("Light or dark", "dark mode night theme", SettingsGroup.Look),
    Triple("Colours", "ink pair palette colour color", SettingsGroup.Look),
    Triple("Your own colours", "custom palette color", SettingsGroup.Look),
    Triple("Display card", "banner photo background top bar picture", SettingsGroup.Yours),
    Triple("Your mascot", "avatar face profile picture", SettingsGroup.Yours),
    Triple("Course names", "alias rename nickname subjects", SettingsGroup.Yours),
    Triple("Notifications", "alerts", SettingsGroup.Alerts),
    Triple("Attendance warnings", "75 percent line alert", SettingsGroup.Alerts),
    Triple("Reminders before class", "before class reminder", SettingsGroup.Alerts),
    Triple("New marks, deadlines, exams, registration", "alerts marks deadline exam registration remarks", SettingsGroup.Alerts),
    Triple("Sync now", "refresh update data", SettingsGroup.Sync),
    Triple("Sync in the background", "background refresh battery", SettingsGroup.Sync),
    Triple("Let daVIT run in the background", "battery optimisation unrestricted", SettingsGroup.Sync),
    Triple("Semester", "term", SettingsGroup.Sync),
    Triple("Calendar class group", "freshers group saturday", SettingsGroup.Sync),
    Triple("App lock", "fingerprint face lock security", SettingsGroup.Phone),
    Triple("Home screen widget", "widget", SettingsGroup.Phone),
    Triple("Attendance widget", "widget", SettingsGroup.Phone),
    Triple("Classes in your calendar", "google calendar phone export", SettingsGroup.Phone),
    Triple("Classes as a file", "ics export share", SettingsGroup.Phone),
    Triple("Downloads", "files offline saved", SettingsGroup.Phone),
    Triple("Campus map", "map refresh navigation", SettingsGroup.Phone),
    Triple("Updates", "version new release install", SettingsGroup.About),
    Triple("Update by itself", "auto update", SettingsGroup.About),
    Triple("Credits", "thanks licence", SettingsGroup.About),
    Triple("Test background sync", "debug", SettingsGroup.About),
    Triple("Save VTOP pages", "debug parser", SettingsGroup.About),
)

/**
 * Settings, behind the mascot: you at the top, Find a setting, then six groups, each with
 * how it's set now, each opening its own short page. Signing out at the end.
 */
@Composable
internal fun SettingsScreen(route: Route) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    val snapshot = state.snapshot

    RouteScreen(route, "Settings") {
        // You, in a line or two: the groups below should fit without scrolling.
        val profile = snapshot?.profile
        InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                profile?.photo?.let { VtopPhoto(it) }
                Column(Modifier.weight(1f)) {
                    InkText(profile?.name?.ifBlank { null } ?: state.regNo.orEmpty(), style = RisoType.title, maxLines = 1)
                    InkText(
                        listOfNotNull(
                            snapshot?.semester?.name?.substringBefore(" - "),
                            profile?.hostel?.room?.ifBlank { null }?.let { "Room $it" },
                        ).joinToString(" · "),
                        style = RisoType.small,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // Starts empty each time Settings opens.
        var query by remember { mutableStateOf("") }
        InkField(query, { query = it }, "Find a setting", modifier = Modifier.fillMaxWidth())
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val hits = SettingsIndex.filter { (name, words, _) -> q in name.lowercase() || q in words }
            Spacer(Modifier.height(10.dp))
            if (hits.isEmpty()) InkText("Nothing called that. Try another word.", style = RisoType.small)
            hits.forEach { (name, _, group) ->
                SettingRow(name, group.title, "Open") { open(nav, group) }
            }
            return@RouteScreen
        }

        // The groups, each with how it's set now.
        Spacer(Modifier.height(8.dp))
        val blocked = state.alerts && !Notifier.permitted(context)
        val saved by repo.files.saved.collectAsState()
        val editorial = LocalInks.current.editorial
        SettingsGroup.entries.forEach { g ->
            val summary = when (g) {
                SettingsGroup.Look -> listOfNotNull(
                    if (editorial) "Editorial" else "Classic",
                    listOf("Follows the phone", "Light", "Dark")[state.theme.coerceIn(0, 2)],
                    if (editorial) null else InkPairs.all[state.palette.base.coerceIn(0, InkPairs.all.lastIndex)].name.takeUnless { state.palette.custom } ?: "your own colours",
                ).joinToString(" · ")
                SettingsGroup.Yours -> "Display card, mascot, course names" + if (state.aliases.isNotEmpty()) " · ${state.aliases.size} renamed" else ""
                SettingsGroup.Alerts -> when {
                    !state.alerts -> "Off"
                    blocked -> "Blocked in Android settings"
                    else -> alertSummary(state.alertPrefs)
                }
                SettingsGroup.Sync -> listOfNotNull(
                    if (state.background) "Every 6 hours" else "Background sync off",
                    snapshot?.semester?.name?.substringBefore(" - "),
                ).joinToString(" · ")
                SettingsGroup.Phone -> listOfNotNull(
                    if (state.appLock) "App lock on" else null,
                    if (saved.isEmpty()) "no downloads" else "${saved.size} download${if (saved.size == 1) "" else "s"}",
                    "widgets, calendar",
                ).joinToString(" · ")
                SettingsGroup.About -> "daVIT ${remember { AutoUpdate.current(context) }}"
            }
            GroupRow(g, summary) { open(nav, g) }
        }

        Spacer(Modifier.height(24.dp))
        var confirm by remember { mutableStateOf(false) }
        if (confirm) {
            InkText("Signs out of VTOP and erases your password, session and cached data from this phone.", style = RisoType.small)
            Spacer(Modifier.height(10.dp))
            InkButton("Yes, sign out", { nav.back(); repo.signOut() }, fillWidth = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            InkButton("Keep me signed in", { confirm = false }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
        } else {
            InkButton("Sign out", { confirm = true }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun open(nav: Nav, g: SettingsGroup) = nav.go(if (g == SettingsGroup.Alerts) Route.Notifications else Route.SettingsPage(g.id))

/** One group on the index: its icon, its name, how it's set now. */
@Composable
private fun GroupRow(g: SettingsGroup, summary: String, onClick: () -> Unit) {
    val inks = LocalInks.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${g.title}: $summary" }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconGlyph(g.icon, inks.text, Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            InkText(g.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
            InkText(summary.replaceFirstChar { it.uppercase() }, style = RisoType.small, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        InkText("›", style = RisoType.title)
    }
    Hairline()
}

/** One group's own page. */
@Composable
internal fun SettingsPageScreen(route: Route.SettingsPage) {
    val g = SettingsGroup.entries.firstOrNull { it.id == route.group } ?: SettingsGroup.Look
    RouteScreen(route, g.title) {
        when (g) {
            SettingsGroup.Look -> LookSettings()
            SettingsGroup.Yours -> YoursSettings()
            SettingsGroup.Alerts -> {}
            SettingsGroup.Sync -> SyncSettings()
            SettingsGroup.Phone -> PhoneSettings()
            SettingsGroup.About -> AboutSettings()
        }
    }
}

@Composable
private fun LookSettings() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    val editorial = LocalInks.current.editorial
    SettingsCard("Style") {
        StylePicker(editorial, repo::setEditorial)
    }
    SettingsCard("Light or dark") {
        ChoiceRow(listOf("System", "Light", "Dark"), state.theme) { repo.setTheme(it) }
    }
    // Colours belong to Classic; Editorial keeps its own newsprint and red.
    if (!editorial) SettingsCard("Colours") {
        val palette = state.palette
        var replacing by remember { mutableStateOf<Int?>(null) }
        InkText(
            if (palette.custom) "Your own, from " + InkPairs.all[palette.base.coerceIn(0, InkPairs.all.lastIndex)].name
            else "Ink pair · " + InkPairs.all[palette.base.coerceIn(0, InkPairs.all.lastIndex)].name,
            style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkPairs.classic.forEach { i -> InkSwatch(InkPairs.of(i, LocalInks.current.dark), selected = !palette.custom && i == palette.base) { if (palette.custom) replacing = i else repo.setInk(i) } }
        }
        replacing?.let { i ->
            Spacer(Modifier.height(8.dp))
            InkText("Start over from " + InkPairs.all[i].name + "? Your own colours go.", style = RisoType.small)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clickable(role = Role.Button) { repo.setInk(i); replacing = null }) { InkChip("Start over", solid = true) }
                Box(Modifier.clickable(role = Role.Button) { replacing = null }) { InkChip("Keep mine") }
            }
        }
        Spacer(Modifier.height(6.dp))
        SettingRow("Your own colours", "Any colour for every ink, and for each kind of card.", "Choose") { nav.go(Route.Colours) }
    }
}

@Composable
private fun YoursSettings() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    SettingsCard("On Home") {
        SettingRow(
            "Display card",
            if (LocalInks.current.editorial) "The picture at the top of Home: a print or your own photo." else "The top bar on a print or your own photo, with two stats you pick.",
            "Change",
        ) { nav.go(Route.DisplayCard) }
        SettingRow(
            "Course names",
            if (state.aliases.isEmpty()) "Your own short names for courses, shown everywhere." else "${state.aliases.size} renamed, shown everywhere.",
            "Rename",
        ) { nav.go(Route.CourseNames) }
    }
    SettingsCard("Your mascot") {
        InkText("The face in the top bar.", style = RisoType.small)
        Spacer(Modifier.height(8.dp))
        MascotEditor(Mascot.parse(state.mascot)) { repo.setMascot(it.encode()) }
    }
}

@Composable
private fun SyncSettings() {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    val snapshot = state.snapshot
    SettingsCard("Sync") {
        InkButton(state.busy ?: "Sync now", repo::sync, enabled = state.busy == null, fillWidth = true, modifier = Modifier.fillMaxWidth())
        if (state.timings.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            SettingRow("Last sync", "${state.timings.sumOf { it.second } / 100 / 10.0} s over ${state.timings.size} stages", if (open) "Hide" else "Stages") { open = !open }
            AnimatedVisibility(open) {
                Column {
                    state.timings.forEach { (stage, ms) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            InkText(stage, style = RisoType.small, modifier = Modifier.weight(1f))
                            InkText("$ms ms", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
        Hairline()
        ToggleRow(
            "Sync in the background",
            "About every 6 hours while the app is closed, never between 1 and 6 am." + (state.lastBackground?.let { " Last: $it." } ?: ""),
            state.background,
        ) { repo.setBackground(!state.background) }
        if (state.background && !BackgroundSync.unrestricted(context)) {
            ToggleRow(
                "Let daVIT run in the background",
                "Your phone pauses apps when the screen is off, which stops background syncs and reminders. Tap to allow.",
                false,
            ) { BackgroundSync.askUnrestricted(context) }
        }
    }

    val semesters = snapshot?.semesters.orEmpty()
    val groups = snapshot?.feeds?.classGroups.orEmpty()
    if (semesters.size > 1 || groups.isNotEmpty()) {
        SettingsCard("Semester") {
            if (semesters.size > 1) {
                semesters.take(4).forEach { sem ->
                    val chosen = sem.id == snapshot?.semester?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !chosen && state.busy == null, role = Role.RadioButton) { repo.chooseSemester(sem.id) }
                            .semantics { selected = chosen }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Radio(chosen)
                        Spacer(Modifier.width(12.dp))
                        InkText(sem.name)
                    }
                }
            }
            if (groups.isNotEmpty()) {
                if (semesters.size > 1) Hairline()
                val manual = repo.manualCalendarGroup()
                val cal = snapshot?.calendar
                Spacer(Modifier.height(8.dp))
                InkText("Calendar class group", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                InkText(
                    when {
                        manual != null -> "You chose ${groups.firstOrNull { it.id == manual }?.let { "${it.id} (${it.name})" } ?: manual}."
                        cal?.group?.isNotBlank() == true -> "Detected from your attendance: ${cal.group}."
                        else -> "Not detected yet; using General."
                    } + " Freshers' Saturdays and some exam days differ by group.",
                    style = RisoType.small,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(listOf("Auto") + groups.map { it.id }, if (manual == null) 0 else groups.indexOfFirst { it.id == manual } + 1) {
                    repo.setCalendarGroup(if (it == 0) null else groups[it - 1].id)
                }
            }
        }
    }
}

@Composable
private fun PhoneSettings() {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    SettingsCard("Security") {
        val activity = context as? androidx.activity.ComponentActivity
        if (activity != null && dev.medriid.davit.MainActivity.canLock(activity)) {
            ToggleRow(
                "App lock",
                "Fingerprint, face or screen lock to open daVIT after a minute away. The widget and notifications still show.",
                state.appLock,
            ) { repo.setAppLock(!state.appLock) }
        } else {
            InkText("This phone has no screen lock set, so daVIT can't ask for one.", style = RisoType.small)
        }
    }
    val widgets = AppWidgetManager.getInstance(context)
    if (widgets.isRequestPinAppWidgetSupported) SettingsCard("Widgets") {
        SettingRow("Home screen widget", "Your class now or next, its attendance, and what's due.", "Add") {
            widgets.requestPinAppWidget(ComponentName(context, DavitWidgetReceiver::class.java), null, null)
        }
        SettingRow("Attendance widget", "Courses near or under the line, worst first, with what it takes.", "Add") {
            widgets.requestPinAppWidget(ComponentName(context, dev.medriid.davit.widget.AttendanceWidgetReceiver::class.java), null, null)
        }
    }
    SettingsCard("Calendar") {
        ClassesInCalendar(state)
    }
    SettingsCard("Stored here") {
        val saved by repo.files.saved.collectAsState()
        SettingRow(
            "Downloads",
            if (saved.isEmpty()) "Files you open from VTOP stay here for offline." else "${saved.size} file${if (saved.size == 1) "" else "s"} on this phone.",
            "Manage",
        ) { nav.go(Route.Downloads) }
        SettingRow("Campus map", "Jeman's map of the campus, kept on this phone and refreshed weekly.", "Refresh") { repo.maps.refresh() }
    }
}

@Composable
private fun AboutSettings() {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    SettingsCard("Updates") {
        val version = remember { AutoUpdate.current(context) }
        val scope = rememberCoroutineScope()
        var update by remember { mutableStateOf<String?>(null) }
        if (AutoUpdate.supported(context)) {
            val ready by AutoUpdate.ready.collectAsState()
            var checking by remember { mutableStateOf(false) }
            val r = ready
            SettingRow(
                "daVIT $version",
                when {
                    r != null -> "${r.version} is downloaded. Install keeps your data and sign-in."
                    else -> update ?: "Check GitHub for a newer release."
                },
                when {
                    r != null -> "Install"
                    checking -> "Checking"
                    else -> "Check"
                },
            ) {
                when {
                    r != null -> AutoUpdate.install(context)
                    !checking -> {
                        checking = true
                        update = "Checking GitHub…"
                        scope.launch {
                            update = try {
                                if (AutoUpdate.check(context) == null) "You're on the latest version." else null
                            } catch (e: Exception) {
                                "Couldn't download the update. Try again later."
                            }
                            checking = false
                        }
                    }
                }
            }
            var auto by remember { mutableStateOf(AutoUpdate.enabled(context)) }
            ToggleRow(
                "Update by itself",
                "Once a day, asks GitHub for a new release and downloads it. Installing is always your tap.",
                auto,
            ) {
                auto = !auto
                AutoUpdate.setEnabled(context, auto)
            }
        } else {
            // Dev builds are a different app; they only point at the release page.
            var release by remember { mutableStateOf<Updates.Release?>(null) }
            SettingRow("daVIT $version", update ?: "Check GitHub for a newer release.", if (release != null) "Get" else "Check") {
                val rel = release
                if (rel != null) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    update = "Checking…"
                    scope.launch {
                        update = try {
                            val latest = withContext(Dispatchers.IO) { Updates.latest() }
                            if (Updates.isNewer(latest.version, version)) {
                                release = latest
                                "${latest.version} is out. Tap Get to see it."
                            } else {
                                "You're on the latest version."
                            }
                        } catch (e: Exception) {
                            "Couldn't reach GitHub. Try again later."
                        }
                    }
                }
            }
        }
    }

    // The people whose work this stands on.
    SettingsCard("Credits") {
        val open = { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
        SettingRow(
            "Campus map by Jeman",
            "The buildings, rooms, PRP floors, mess menus and walking routes in Navigation are his work, shared under the MIT licence. Thank you, Jeman.",
            "Jeman",
        ) { open("https://www.linkedin.com/in/jemankalita/") }
        SettingRow("Map data", "© OpenStreetMap contributors, under the Open Database Licence.", "OSM") { open("https://www.openstreetmap.org/copyright") }
        InkText("Fonts: Bagel Fat One and IBM Plex Mono, under the SIL Open Font Licence.", style = RisoType.small, modifier = Modifier.padding(top = 6.dp))
    }

    // For fixing things.
    SettingsCard("Debugging") {
        var queued by remember { mutableStateOf(false) }
        // A new "Last" line means the queued run happened.
        LaunchedEffect(state.lastBackground) { queued = false }
        SettingRow(
            "Test background sync",
            if (queued) "Queued: lock the phone now, then check \"Last\" under Sync in the background in a minute."
            else "Runs one background sync in 30 seconds.",
            if (queued) "Queued" else "Run",
        ) {
            BackgroundSync.testSoon(context)
            queued = true
        }
        ToggleRow(
            "Save VTOP pages",
            "Keeps the last raw page of each kind on this phone so a broken parser can be fixed. Your username and tokens are scrubbed.",
            state.dumpPages,
        ) { repo.setDumpPages(!state.dumpPages) }
    }
}

/**
 * What to notify about: attendance lines, skips, muted courses, reminders before class,
 * and each other kind of alert on its own switch.
 */
@Composable
internal fun NotificationsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    val p = state.alertPrefs
    fun set(next: AlertPrefs) = repo.setAlertPrefs(next)
    val blocked = state.alerts && !Notifier.permitted(context)
    val openAndroid = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    RouteScreen(route, "Notifications", if (state.alerts) alertSummary(p) else "Off") {
        InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
            ToggleRow(
                "Notifications",
                if (blocked) "Blocked in Android settings. Tap to open them." else "Checked after each background sync; reminders at the minute you set.",
                state.alerts,
            ) { if (blocked) openAndroid() else repo.setAlerts(!state.alerts) }
        }
        if (!state.alerts) return@RouteScreen

        val exempt = state.snapshot?.attendanceExempt == true
        SettingsCard("Attendance") {
            if (exempt) {
                InkText("Your CGPA is 9 or more, so attendance doesn't hold you back. These stay off until it does.", style = RisoType.small)
                Spacer(Modifier.height(8.dp))
            }
            ToggleRow("Attendance warnings", "When a course slips, once per change.", p.attendance) { set(p.copy(attendance = !p.attendance)) }
            if (p.attendance) {
                Spacer(Modifier.height(6.dp))
                InkText("Warn me below", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                InkText("75% is VTOP's line; a higher one warns while there's still room.", style = RisoType.small)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(AlertPrefs.WarnLines.map { "$it%" }, AlertPrefs.WarnLines.indexOf(p.warnBelow).coerceAtLeast(0)) { set(p.copy(warnBelow = AlertPrefs.WarnLines[it])) }
                Spacer(Modifier.height(14.dp))
                InkText("When skips run low", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                InkText("Skips are classes you can miss in a row and stay at 75%.", style = RisoType.small)
                Spacer(Modifier.height(8.dp))
                ChoiceRow(listOf("Never", "None left", "1 left", "2 left", "3 left"), AlertPrefs.SkipOptions.indexOf(p.skipsLeft).coerceAtLeast(0)) {
                    set(p.copy(skipsLeft = AlertPrefs.SkipOptions[it]))
                }
            }
        }

        // Each course on its own switch, the closest to the line first.
        val rows = state.snapshot?.attendance.orEmpty().sortedBy { it.percent }
        if (p.attendance && rows.isNotEmpty()) {
            SettingsCard("Courses") {
                InkText("Mute a course you've made peace with; the rest still warn.", style = RisoType.small)
                Spacer(Modifier.height(4.dp))
                rows.forEach { r ->
                    val key = courseKey(r.courseCode, r.classType)
                    val on = key !in p.muted
                    ToggleRow(r.courseName, "${r.classType} · ${r.percent}% · ${r.attended}/${r.total}", on) {
                        set(p.copy(muted = if (on) p.muted + key else p.muted - key))
                    }
                }
            }
        }

        SettingsCard("Before class") {
            InkText("A reminder before class, with the room and what missing it would cost.", style = RisoType.small)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(AlertPrefs.BeforeClassOptions.map { if (it == 0) "Off" else "$it min" }, AlertPrefs.BeforeClassOptions.indexOf(p.beforeClass).coerceAtLeast(0)) {
                set(p.copy(beforeClass = AlertPrefs.BeforeClassOptions[it]))
            }
            if (p.beforeClass > 0) {
                Spacer(Modifier.height(14.dp))
                InkText("For", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                ChoiceRow(listOf("Courses near the line", "Every class"), if (p.beforeClassNearLine) 0 else 1) { set(p.copy(beforeClassNearLine = it == 0)) }
                Spacer(Modifier.height(6.dp))
                InkText(
                    if (p.beforeClassNearLine) "Near the line: below ${p.warnBelow}%, or two skips or fewer from 75%."
                    else "Every class, with its room.",
                    style = RisoType.small,
                )
            }
        }

        SettingsCard("Also tell me about") {
            ToggleRow("VTOP remarks", "A course marked Debarred or Revoked, or a change in which exams it covers.", p.remarks) { set(p.copy(remarks = !p.remarks)) }
            ToggleRow("New marks", "A quiz, CAT or assignment score as it appears.", p.marks) { set(p.copy(marks = !p.marks)) }
            ToggleRow("Deadlines", "Assignments due today or tomorrow, and your own reminders.", p.deadlines) { set(p.copy(deadlines = !p.deadlines)) }
            ToggleRow("Exams", "The day before, with the time, venue and seat.", p.exams) { set(p.copy(exams = !p.exams)) }
            ToggleRow("Registration", "A new registration window, and 30 minutes before it opens.", p.notices) { set(p.copy(notices = !p.notices)) }
        }

        SettingsCard("Check") {
            var sent by remember { mutableStateOf(false) }
            SettingRow("Send a test", if (sent) "Sent. If nothing showed up, check Android's settings below." else "Posts one notification now, so you see how they look.", "Send") {
                Notifier.post(
                    context,
                    listOf(
                        dev.medriid.davit.data.Alert(
                            dev.medriid.davit.data.AlertChannel.Classes, "test:${System.currentTimeMillis()}",
                            "daVIT notifications work", "This is how a reminder looks.",
                        ),
                    ),
                )
                sent = true
            }
            SettingRow("Sounds and channels", "Android's own settings for each kind of notification.", "Open") { openAndroid() }
        }
    }
}

/** "Below 80% · 1 skip left · 10 min before class". */
private fun alertSummary(p: AlertPrefs): String = listOfNotNull(
    if (!p.attendance) "No attendance warnings" else "Below ${p.warnBelow}%",
    when {
        !p.attendance || p.skipsLeft < 0 -> null
        p.skipsLeft == 0 -> "no skips left"
        else -> "${p.skipsLeft} skip${if (p.skipsLeft == 1) "" else "s"} left"
    },
    if (p.beforeClass > 0) "${p.beforeClass} min before class" else null,
).joinToString(" · ")

/** A titled card of settings, with an optional glyph by the title. */
@Composable
internal fun SettingsCard(title: String, icon: RisoIcon? = null, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconGlyph(icon, LocalInks.current.a, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            InkText(title, label = true)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
internal fun ToggleRow(title: String, body: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = onToggle)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
            InkText(body, style = RisoType.small)
        }
        Spacer(Modifier.width(12.dp))
        InkChip(if (on) "On" else "Off", solid = on)
    }
}

@Composable
internal fun SettingRow(title: String, body: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            InkText(title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
            InkText(body, style = RisoType.small)
        }
        Spacer(Modifier.width(12.dp))
        InkChip(action, solid = true)
    }
}

@Composable
private fun Radio(on: Boolean) {
    val inks = LocalInks.current
    Box(
        Modifier
            .size(18.dp)
            .border(2.dp, inks.text, CircleShape)
            .padding(4.dp)
            .then(if (on) Modifier.background(inks.a, CircleShape) else Modifier),
    )
}

/** VTOP's profile photo, decoded once, in a small ink-framed square. */
@Composable
private fun VtopPhoto(dataUri: String) {
    val inks = LocalInks.current
    val bitmap = remember(dataUri) {
        runCatching {
            val bytes = android.util.Base64.decode(dataUri.substringAfter(','), android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(
        bitmap, contentDescription = "Your VTOP photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier.padding(end = 14.dp).size(48.dp).clip(CardShape).border(2.dp, inks.text, CardShape),
    )
}

/**
 * Your classes in the phone's calendar app (asks for the calendar permission when you
 * turn it on), kept up to date after each sync; or the semester as a file for other apps.
 */
@Composable
private fun ClassesInCalendar(state: dev.medriid.davit.data.UiState) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = state.snapshot
    var on by remember { mutableStateOf(PhoneCalendar.enabled(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    val color = LocalInks.current.a.toArgb()
    fun add() = scope.launch {
        note = "Adding your classes…"
        note = runCatching { PhoneCalendar.turnOn(context, snapshot!!.classEvents(java.time.LocalDate.now()), color) }
            .fold({ n -> on = true; "$n classes added to \"daVIT classes\" in your calendar." }, { "Couldn't write to the phone's calendar." })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) add() else note = "daVIT needs the calendar permission for this."
    }
    when {
        snapshot?.calendar == null -> SettingRow("Classes in your calendar", "Needs the academic calendar, which comes with the next sync.", "Later") {}
        on -> SettingRow("Classes in your calendar", note ?: "On: \"daVIT classes\", updated after each sync. Holidays and swapped days included.", "Remove") {
            scope.launch {
                PhoneCalendar.turnOff(context)
                on = false
                note = "Removed from your calendar."
            }
        }
        else -> SettingRow("Classes in your calendar", note ?: "Every class until the semester ends, holidays and swapped days included, in your phone's calendar app.", "Add") {
            if (PhoneCalendar.permitted(context)) add()
            else ask.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
        }
    }
    if (snapshot?.calendar != null) {
        SettingRow("Classes as a file", "An .ics file of the semester, for calendar apps that import one.", "Share") {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    java.io.File(context.cacheDir, "exports").apply { mkdirs() }.resolve("daVIT classes.ics").apply {
                        writeText(icsOf(snapshot.classEvents(java.time.LocalDate.now()), java.time.Instant.now()))
                    }
                }
                repo.files.share(file)
            }
        }
    }
}

/** Two overlapping ink discs; the overlap is whatever multiply makes of them. */
@Composable
internal fun InkSwatch(pair: Inks, selected: Boolean, onClick: () -> Unit) {
    val current = LocalInks.current
    Box(
        Modifier
            .size(56.dp)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) current.text else current.rule, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = pair.name
                this.selected = selected
            }
            .padding(11.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.34f
            drawCircle(pair.a, r, Offset(r, r), blendMode = current.blend)
            drawCircle(pair.b, r, Offset(size.width - r, size.height - r), blendMode = current.blend)
        }
    }
}
