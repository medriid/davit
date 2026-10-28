package dev.medriid.davit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import dev.medriid.davit.data.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import dev.medriid.davit.data.UiState
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.Notifier
import dev.medriid.davit.widget.DavitWidgetReceiver
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule

/** Behind the mascot: who you are, how it looks, and the escape hatches. */
@Composable
internal fun AccountSheet(
    visible: Boolean,
    state: UiState,
    mascot: Mascot,
    onMascot: (Mascot) -> Unit,
    onInk: (Int) -> Unit,
    onSemester: (String) -> Unit,
    onSync: () -> Unit,
    onDumpPages: (Boolean) -> Unit,
    onBackground: (Boolean) -> Unit,
    onAlerts: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    val inks = LocalInks.current
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) confirmSignOut = false }

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(inks.text.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .background(inks.paper, CardShape)
                    .border(2.dp, inks.text, CardShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter, vertical = 20.dp),
            ) {
                Heading("Account", 24.sp)
                Spacer(Modifier.height(4.dp))
                InkText(state.regNo ?: "", style = RisoType.title)
                state.snapshot?.let { InkText(it.semester.name, style = RisoType.small) }

                SheetLabel("Your mascot")
                MascotEditor(mascot, onMascot)

                val semesters = state.snapshot?.semesters.orEmpty()
                if (semesters.size > 1) {
                    SheetLabel("Semester")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        semesters.take(4).forEach { sem ->
                            val chosen = sem.id == state.snapshot?.semester?.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !chosen && state.busy == null, role = Role.RadioButton) { onSemester(sem.id) }
                                    .semantics { selected = chosen }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .border(2.dp, inks.text, CircleShape)
                                        .padding(4.dp)
                                        .then(if (chosen) Modifier.background(inks.a, CircleShape) else Modifier),
                                )
                                Spacer(Modifier.width(12.dp))
                                InkText(sem.name)
                            }
                        }
                    }
                }

                SheetLabel("Ink pair")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkPairs.all.forEachIndexed { i, pair ->
                        InkSwatch(pair, selected = i == state.inkIndex) { onInk(i) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                InkText(InkPairs.all[state.inkIndex.coerceIn(0, InkPairs.all.lastIndex)].name, style = RisoType.small)

                SheetLabel("Sync")
                InkButton(if (state.busy == null) "Sync now" else state.busy, onSync, enabled = state.busy == null, fillWidth = true, modifier = Modifier.fillMaxWidth())
                if (state.timings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    InkText("Last sync, by stage", label = true)
                    Spacer(Modifier.height(4.dp))
                    state.timings.forEach { (stage, ms) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            InkText(stage, style = RisoType.small, modifier = Modifier.weight(1f))
                            InkText("$ms ms", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        InkText("total", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        InkText("${state.timings.sumOf { it.second }} ms", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
                    }
                }

                SheetLabel("Background")
                ToggleRow(
                    "Sync in the background",
                    "About every 6 hours while the app is closed, never between 1 and 6 am." +
                        (state.lastBackground?.let { " Last: $it." } ?: ""),
                    state.background,
                ) {
                    onBackground(!state.background)
                }
                val context = LocalContext.current
                if (state.background && !BackgroundSync.unrestricted(context)) {
                    ToggleRow(
                        "Let daVIT run in the background",
                        "Your phone pauses apps when the screen is off, which stops background syncs. Tap to allow.",
                        false,
                    ) { BackgroundSync.askUnrestricted(context) }
                }
                val widgets = AppWidgetManager.getInstance(context)
                if (widgets.isRequestPinAppWidgetSupported) {
                    ActionRow("Home screen widget", "Your class now or next, its attendance, and what's due.", "Add") {
                        widgets.requestPinAppWidget(ComponentName(context, DavitWidgetReceiver::class.java), null, null)
                    }
                }
                val blocked = state.alerts && !Notifier.permitted(context)
                ToggleRow(
                    "Notifications",
                    if (blocked) "Blocked in Android settings. Tap to open them."
                    else "Attendance near 75%, new marks, deadlines and exams.",
                    state.alerts,
                ) {
                    if (blocked) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        onAlerts(!state.alerts)
                    }
                }

                SheetLabel("About")
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                }
                val scope = rememberCoroutineScope()
                var update by remember { mutableStateOf<String?>(null) }
                var release by remember { mutableStateOf<Updates.Release?>(null) }
                ActionRow(
                    "daVIT $version",
                    update ?: "Check GitHub for a newer release. Only happens when you tap.",
                    if (release != null) "Get" else "Check",
                ) {
                    val r = release
                    if (r != null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        update = "Checking…"
                        scope.launch {
                            update = try {
                                val latest = withContext(Dispatchers.IO) { Updates.latest() }
                                if (Updates.isNewer(latest.version, version)) {
                                    release = latest
                                    "${latest.version} is out. Tap Get to download it."
                                } else {
                                    "You're on the latest version."
                                }
                            } catch (e: Exception) {
                                "Couldn't reach GitHub. Try again later."
                            }
                        }
                    }
                }

                SheetLabel("Debugging")
                var queued by remember { mutableStateOf(false) }
                // A new "Last" line means the queued run happened.
                LaunchedEffect(state.lastBackground) { queued = false }
                ActionRow(
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
                ) { onDumpPages(!state.dumpPages) }

                Spacer(Modifier.height(18.dp))
                Rule()
                Spacer(Modifier.height(18.dp))
                if (confirmSignOut) {
                    InkText("Signs out of VTOP and erases your password, session and cached data from this phone.", style = RisoType.small)
                    Spacer(Modifier.height(10.dp))
                    InkButton("Yes, sign out", onSignOut, fillWidth = true, modifier = Modifier.fillMaxWidth())
                } else {
                    InkButton("Sign out", { confirmSignOut = true }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                InkButton("Close", onClose, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
        }
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
private fun ActionRow(title: String, body: String, action: String, onClick: () -> Unit) {
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
private fun SheetLabel(text: String) {
    Spacer(Modifier.height(22.dp))
    InkText(text, label = true)
    Spacer(Modifier.height(10.dp))
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
            drawCircle(pair.a, r, Offset(r, r), blendMode = BlendMode.Multiply)
            drawCircle(pair.b, r, Offset(size.width - r, size.height - r), blendMode = BlendMode.Multiply)
        }
    }
}
