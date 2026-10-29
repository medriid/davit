package dev.medriid.davit

import android.app.Application
import dev.medriid.davit.data.Repo
import dev.medriid.davit.sync.BackgroundSync
import dev.medriid.davit.sync.Notifier

class DavitApp : Application() {
    val repo: Repo by lazy { Repo(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        // Debug builds can show generic demo data (src/debug); release builds have a no-op here.
        Demo.maybeShow(this, repo)
        // Re-enqueue in case it was lost (KEEP leaves an existing schedule alone).
        repo.state.value.let { BackgroundSync.apply(this, it.signedIn && it.background) }
    }
}
