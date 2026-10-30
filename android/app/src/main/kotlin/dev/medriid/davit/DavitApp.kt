package dev.medriid.davit

import android.app.Application
import dev.medriid.davit.data.Repo
import dev.medriid.davit.sync.AutoUpdate
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.ClassAlarms
import dev.medriid.davit.sync.Notifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DavitApp : Application() {
    val repo: Repo by lazy { Repo(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        // Debug builds can show generic demo data (src/debug); release builds have a no-op here.
        Demo.maybeShow(this, repo)
        // Re-enqueue in case it was lost (KEEP leaves an existing schedule alone).
        repo.state.value.let { BackgroundSync.apply(this, it.signedIn && it.background) }
        // A download from before, and the daily check for a new release.
        AutoUpdate.start(this)
        // Before-class reminders follow the timetable, attendance and your settings.
        repo.scope.launch {
            repo.state
                .map { s -> listOf(s.signedIn && s.alerts, s.alertPrefs, s.snapshot?.timetable, s.snapshot?.attendance, s.snapshot?.calendar) }
                .distinctUntilChanged()
                .collect { ClassAlarms.reschedule(this@DavitApp) }
        }
    }
}
