package dev.medriid.davit.sync

import dev.medriid.davit.data.key
import dev.medriid.davit.data.fixedBetween
import dev.medriid.davit.data.withLabSessions
import dev.medriid.davit.data.withAliases
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.medriid.davit.DavitApp
import dev.medriid.davit.data.Alert
import dev.medriid.davit.data.AlertChannel
import dev.medriid.davit.data.alertsFor
import dev.medriid.davit.vtop.BadCredentials
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** The next background sync after [now]: x:15 or x:45, none between 01:00 and 06:00. */
internal fun nextSyncAt(now: LocalDateTime): LocalDateTime {
    val hour = now.truncatedTo(ChronoUnit.HOURS)
    val slot = listOf(15L, 45L, 75L).map { hour.plusMinutes(it) }.first { it.isAfter(now) }
    return if (slot.hour in 1..5) slot.withHour(6).withMinute(15) else slot
}

/**
 * Syncs at x:15 and x:45 while the app is closed, on any network. WorkManager can't keep to
 * the clock, so the times are an alarm, the same way as [ClassAlarms]: the next one set again
 * after each fires and after a reboot. Nothing runs between 01:00 and 06:00, and a failed
 * attempt just waits for the next slot.
 */
object BackgroundSync {
    /** The old six-hourly periodic work, cancelled for phones updating from it. */
    private const val WORK = "davit.sync"
    private const val NOW = "davit.sync.now"
    const val ACTION = "dev.medriid.davit.SYNC"

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
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        if (!on) {
            am.cancel(pi)
            return
        }
        val at = nextSyncAt(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Without exact alarms, a short window that starts at the slot.
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pi)
        }
    }

    /** A slot's sync: as soon as there's a network, unless the last one is still waiting for it. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // Before Android 12 expedited work needs a foreground notification; not worth one here.
        if (Build.VERSION.SDK_INT >= 31) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request.build())
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SyncAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val st = (context.applicationContext as DavitApp).repo.state.value
        val on = st.signedIn && st.background
        if (on && intent.action == BackgroundSync.ACTION) BackgroundSync.runNow(context)
        // After a slot, a reboot or a time change: set the next one.
        BackgroundSync.apply(context, on)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (LocalTime.now().hour in 1..5) return Result.success()
        val repo = (applicationContext as DavitApp).repo
        val (before, after, error) = repo.syncForBackground()
        Log.i("daVIT", "background sync: " + (error?.javaClass?.simpleName ?: "ok"))
        return when {
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
