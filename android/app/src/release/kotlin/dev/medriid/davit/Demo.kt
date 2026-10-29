package dev.medriid.davit

import android.app.Application
import dev.medriid.davit.data.Repo

/** Release builds never show demo data. */
internal object Demo {
    fun maybeShow(app: Application, repo: Repo) = Unit
}
