package dev.medriid.davit.ui

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

/**
 * Everything you can set, behind the mascot: you, notifications, sync, semester, the
 * look, this phone, VTOP's other pages, about, and signing out. Grouped in cards so each
 * setting has a place you can find again.
 */
@Composable
internal fun SettingsScreen(route: Route) {
    val repo = LocalRepo.current
    val nav = LocalNav.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    val snapshot = state.snapshot

    RouteScreen(route, "Settings") {
        // You.
        val profile = snapshot?.profile
        InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                profile?.photo?.let { VtopPhoto(it) }
                Column(Modifier.weight(1f)) {
                    InkText(profile?.name?.ifBlank { null } ?: state.regNo.orEmpty(), style = RisoType.title, maxLines = 2)
                    if (profile?.name?.isNotBlank() == true) InkText(state.regNo.orEmpty(), style = RisoType.small)
                    snapshot?.let { InkText(it.semester.name, style = RisoType.small) }
                }
            }
            profile?.hostel?.let { h ->
                Spacer(Modifier.height(12.dp))
                Hairline()
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    listOf(h.block, h.room.ifBlank { null }?.let { "Room $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                    listOf(h.bed, h.mess).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Your hostel" },
                    "Leave",
                ) { nav.go(Route.Hostel) }
            }
        }

        // Notifications.
        val blocked = state.alerts && !Notifier.permitted(context)
        SettingsCard("Notifications", RisoIcon.Bell) {
            SettingRow(
                "What to tell you, and when",
                when {
                    !state.alerts -> "Off."
                    blocked -> "Blocked in Android settings."
                    else -> alertSummary(state.alertPrefs)
                },
                "Set up",
            ) { nav.go(Route.Notifications) }
        }

        // Sync.
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

        // Semester and calendar.
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

        // The look.
        SettingsCard("Look") {
            InkText("Paper", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            ChoiceRow(listOf("System", "Paper", "Night print"), state.theme) { repo.setTheme(it) }
            Spacer(Modifier.height(14.dp))
            val palette = state.palette
            var replacing by remember { mutableStateOf<Int?>(null) }
            InkText(
                if (palette.custom) "Colours · your own, from " + InkPairs.all[palette.base.coerceIn(0, InkPairs.all.lastIndex)].name
                else "Ink pair · " + InkPairs.all[palette.base.coerceIn(0, InkPairs.all.lastIndex)].name,
                style = RisoType.body.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InkPairs.all.forEachIndexed { i, pair -> InkSwatch(pair, selected = !palette.custom && i == palette.base) { if (palette.custom) replacing = i else repo.setInk(i) } }
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
            Spacer(Modifier.height(14.dp))
            Hairline()
            var editing by rememberSaveable { mutableStateOf(false) }
            SettingRow("Your mascot", "The face in the top bar.", if (editing) "Done" else "Edit") { editing = !editing }
            AnimatedVisibility(editing) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    MascotEditor(Mascot.parse(state.mascot)) { repo.setMascot(it.encode()) }
                }
            }
        }

        // This phone.
        SettingsCard("On this phone") {
            val activity = context as? androidx.activity.ComponentActivity
            if (activity != null && dev.medriid.davit.MainActivity.canLock(activity)) {
                ToggleRow(
                    "App lock",
                    "Fingerprint, face or screen lock to open daVIT after a minute away. The widget and notifications still show.",
                    state.appLock,
                ) { repo.setAppLock(!state.appLock) }
            }
            val widgets = AppWidgetManager.getInstance(context)
            if (widgets.isRequestPinAppWidgetSupported) {
                SettingRow("Home screen widget", "Your class now or next, its attendance, and what's due.", "Add") {
                    widgets.requestPinAppWidget(ComponentName(context, DavitWidgetReceiver::class.java), null, null)
                }
            }
            val saved by repo.files.saved.collectAsState()
            SettingRow(
                "Downloads",
                if (saved.isEmpty()) "Files you open from VTOP stay here for offline." else "${saved.size} file${if (saved.size == 1) "" else "s"} on this phone.",
                "Manage",
            ) { nav.go(Route.Downloads) }
        }

        // VTOP's other pages.
        SettingsCard("From VTOP") {
            SettingRow("More from VTOP", "Messages, Spot-light, proctor and HOD, gate log, receipts, curriculum, semester GPA.", "Open") { nav.go(Route.Campus) }
            if (profile?.hostel != null) SettingRow("Hostel", "Apply for leave; leave and night-slip status.", "Open") { nav.go(Route.Hostel) }
        }

        // About.
        SettingsCard("About") {
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
            SettingRow("Campus map", "Jeman's map of the campus, kept on this phone and refreshed weekly.", "Refresh") { repo.maps.refresh() }
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
            SettingRow("Calendar and faculty", "The academic calendar and faculty directory come from IshanJ25's public data.", "GitHub") { open("https://github.com/IshanJ25") }
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
private fun SettingsCard(title: String, icon: RisoIcon? = null, content: @Composable ColumnScope.() -> Unit) {
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
private fun ToggleRow(title: String, body: String, on: Boolean, onToggle: () -> Unit) {
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
private fun SettingRow(title: String, body: String, action: String, onClick: () -> Unit) {
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
        modifier = Modifier.padding(end = 14.dp).size(60.dp).clip(CardShape).border(2.dp, inks.text, CardShape),
    )
}

/** Two overlapping ink discs; the overlap is whatever multiply makes of them. */
@Composable
private fun InkSwatch(pair: Inks, selected: Boolean, onClick: () -> Unit) {
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
