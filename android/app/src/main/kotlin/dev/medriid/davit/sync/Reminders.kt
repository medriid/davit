package dev.medriid.davit.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.medriid.davit.DavitApp
import dev.medriid.davit.data.Alert
import dev.medriid.davit.data.AlertChannel
import dev.medriid.davit.vtop.RegistrationWindow
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Local reminders at a set time (30 minutes before registration opens). Nothing is
 * fetched: the worker only posts the notification. WorkManager may run it a few
 * minutes late when the phone is dozing.
 */
object Reminders {
    private const val TAG = "davit.reminder"

    fun registration(context: Context, w: RegistrationWindow) {
        val start = w.start ?: return
        val at = start.minusMinutes(30)
        val wait = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(0)
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val data = workDataOf(
            "key" to "regsoon:$start",
            "title" to "Registration opens at ${start.format(clock)}",
            "text" to listOfNotNull(w.semester.ifBlank { null }, w.end?.let { "until ${it.format(clock)}" }).joinToString(" · "),
        )
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(wait, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG.$start", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as DavitApp).repo
        if (!repo.state.value.signedIn || !repo.state.value.alerts) return Result.success()
        val key = inputData.getString("key") ?: return Result.success()
        Notifier.post(
            applicationContext,
            listOf(Alert(AlertChannel.Notices, key, inputData.getString("title").orEmpty(), inputData.getString("text").orEmpty())),
        )
        return Result.success()
    }
}
