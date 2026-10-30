package dev.medriid.davit.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.medriid.davit.data.Repo
import dev.medriid.davit.data.UiState
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.Notifier

/**
 * Once, after the first sync: the few things worth deciding now, each a row you can skip.
 * Notifications, syncing with the screen off, reminders before class, and which semester
 * you're seeing. Everything here is in Settings too.
 */
@Composable
internal fun SetupScreen(state: UiState, repo: Repo, onDone: () -> Unit) {
    val context = LocalContext.current
    // Android's own screens come back here; re-read what they changed when we're resumed.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    val allowed = remember(checks) { Notifier.permitted(context) }
    val unrestricted = remember(checks) { BackgroundSync.unrestricted(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalInks.current.paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 24.dp),
    ) {
        Heading("Set up", 36.sp)
        Spacer(Modifier.height(6.dp))
        InkText("A few things, each one optional. They're all in Settings later.")

        SettingsCard("Notifications", RisoIcon.Bell) {
            if (allowed) {
                InkText("On. You'll hear about low attendance, new marks, deadlines and exams.", style = RisoType.small)
            } else {
                SettingRow("Let daVIT notify you", "Low attendance, new marks, what's due and exams. Nothing else.", "Allow") {
                    repo.askedForNotifications = true
                    if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }

        SettingsCard("Sync") {
            ToggleRow("Sync in the background", "About every 6 hours while the app is closed, never between 1 and 6 am.", state.background) {
                repo.setBackground(!state.background)
            }
            if (state.background && !unrestricted) {
                SettingRow(
                    "Let it run with the screen off",
                    "Some phones (OnePlus, Oppo, Realme, Xiaomi) pause apps in the background, which stops syncs and alerts.",
                    "Allow",
                ) { BackgroundSync.askUnrestricted(context) }
            }
        }

        SettingsCard("Before class") {
            val p = state.alertPrefs
            ToggleRow("Remind me before class", "10 minutes before, with the room, for courses near the 75% line.", p.beforeClass > 0) {
                repo.setAlertPrefs(p.copy(beforeClass = if (p.beforeClass > 0) 0 else 10, beforeClassNearLine = true))
            }
        }

        val snapshot = state.snapshot
        if (snapshot != null && snapshot.semesters.size > 1) {
            SettingsCard("Semester") {
                InkText("Showing ${snapshot.semester.name}.", style = RisoType.small)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(snapshot.semesters.take(3).map { it.name.substringBefore(" - ") }, snapshot.semesters.take(3).indexOfFirst { it.id == snapshot.semester.id }) { i ->
                    snapshot.semesters.getOrNull(i)?.takeIf { it.id != snapshot.semester.id }?.let { repo.chooseSemester(it.id) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        InkButton("Done", {
            repo.askedForNotifications = true
            onDone()
        }, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}
