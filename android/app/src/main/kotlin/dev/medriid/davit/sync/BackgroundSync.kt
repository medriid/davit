package dev.medriid.davit.sync

import dev.medriid.davit.data.key
import dev.medriid.davit.data.fixedBetween
import dev.medriid.davit.data.withLabSessions
import dev.medriid.davit.data.withAliases
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.medriid.davit.DavitApp
import dev.medriid.davit.data.Alert
import dev.medriid.davit.data.AlertChannel
import dev.medriid.davit.data.alertsFor
import dev.medriid.davit.vtop.BadCredentials
import dev.medriid.davit.vtop.Offline
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Syncs every ~6 hours while the app is closed, on any network. Polite to VTOP: a random
 * first delay spreads phones out, nothing runs between 01:00 and 06:00, and a failed
 * network attempt backs off instead of hammering.
 */
object BackgroundSync {
    private const val WORK = "davit.sync"

    /**
     * Whether the phone lets daVIT run with the screen off. Battery managers (ColorOS,
     * MIUI…) freeze restricted apps a few seconds into a background job.
     */
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Android's own "let this app run in the background" dialog, or its list if that's unavailable. */
    @SuppressLint("BatteryLife")
    fun askUnrestricted(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val intent = if (direct.resolveActivity(context.packageManager) != null) direct else list
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * One background sync in 30 seconds, for checking it works with the screen off.
     * (Periodic work can't be forced: WorkManager skips a run that's ahead of schedule.)
     */
    fun testSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK.test", ExistingWorkPolicy.REPLACE, request)
    }

    fun apply(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Random.nextLong(30, 90), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (LocalTime.now().hour in 1..5) return Result.success()
        val repo = (applicationContext as DavitApp).repo
        val (before, after, error) = repo.syncForBackground()
        Log.i("daVIT", "background sync: " + (error?.javaClass?.simpleName ?: "ok"))
        return when {
            error is Offline -> Result.retry()
            error is BadCredentials -> {
                Notifier.post(
                    applicationContext,
                    listOf(
                        Alert(
                            AlertChannel.Attendance, "creds:${LocalDate.now().withDayOfMonth(1)}",
                            "daVIT can't sign in to VTOP",
                            "VTOP rejected the saved password. Open daVIT, sign out and sign in again.",
                        ),
                    ),
                )
                Result.success()
            }
            error != null || after == null -> Result.success()
            else -> {
                if (repo.state.value.alerts) {
                    // As the app shows them: labs in sessions, your names for courses.
                    val names = repo.state.value.aliases
                    val shown = after.withLabSessions().withAliases(names)
                    // Days you flagged that VTOP has now put right.
                    val fixed = fixedBetween(before, after, repo.state.value.corrections).map { c ->
                        val course = shown.attendance.firstOrNull { it.key == c.course }?.courseName ?: c.course.substringBefore('|')
                        Alert(AlertChannel.Attendance, "fixed|${c.id}", "VTOP fixed your attendance", "$course, ${c.date}: now ${if (c.onDuty) "on duty" else "present"}.")
                    }
                    val alerts = alertsFor(before?.withLabSessions()?.withAliases(names), shown, LocalDate.now(), repo.state.value.reminders, repo.state.value.alertPrefs) + fixed
                    Log.i("daVIT", "background sync: ${alerts.size} alert(s)")
                    Notifier.post(applicationContext, alerts)
                }
                Result.success()
            }
        }
    }
}
