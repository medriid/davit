package dev.medriid.davit.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.medriid.davit.R
import dev.medriid.davit.data.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Keeps daVIT up to date: once a day it asks GitHub for the latest release, downloads a
 * newer APK in the background and says so; installing is always your tap, through
 * Android's own installer. Dev builds (a different app id) never update themselves.
 */
object AutoUpdate {
    private const val WORK = "davit.update"
    private const val PREFS = "davit.updates"
    private const val K_ON = "on"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION = 0x0da7
    private const val SCARLET = 0xFFF65058.toInt()

    /** A downloaded release waiting for you to install it. */
    data class Ready(val version: String, val apk: File)

    private val _ready = MutableStateFlow<Ready?>(null)
    val ready: StateFlow<Ready?> = _ready.asStateFlow()

    fun supported(context: Context) = !context.packageName.endsWith(".dev")

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, true)

    fun current(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun dir(context: Context) = File(context.filesDir, "updates")

    /** At app start: pick up a download from before, drop ones already installed, and schedule the daily check. */
    fun start(context: Context) {
        if (!supported(context)) return
        val current = current(context)
        dir(context).listFiles().orEmpty().forEach { f ->
            val version = f.name.removePrefix("davit-").removeSuffix(".apk")
            if (f.name.endsWith(".apk") && Updates.isNewer(version, current)) {
                if (_ready.value?.let { Updates.isNewer(version, it.version) } != false) _ready.value = Ready(version, f)
            } else {
                f.delete()
            }
        }
        if (_ready.value == null) cancelNotification(context)
        schedule(context, enabled(context))
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()
        schedule(context, on)
    }

    private fun schedule(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS, 6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Random.nextLong(10, 120), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Asks GitHub and downloads a newer release if there is one: the version now ready,
     * or null when you're up to date. Throws on network or download errors.
     */
    suspend fun check(context: Context): Ready? = withContext(Dispatchers.IO) {
        val current = current(context)
        val latest = Updates.latest()
        if (!Updates.isNewer(latest.version, current) || latest.apk == null) return@withContext null
        _ready.value?.takeIf { it.version == latest.version && it.apk.exists() }?.let { return@withContext it }
        dir(context).listFiles().orEmpty().forEach { it.delete() }
        val apk = Updates.download(latest, File(dir(context), "davit-${latest.version}.apk"))
        Ready(latest.version, apk).also { _ready.value = it }
    }

    /** Hands the downloaded APK to Android's installer, or first to "Install unknown apps" if daVIT isn't allowed yet. */
    fun install(context: Context) {
        val r = _ready.value ?: return
        val intent = if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
        } else {
            installIntent(context, r)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun installIntent(context: Context, r: Ready): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", r.apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    internal fun notifyReady(context: Context, r: Ready) {
        if (!Notifier.permitted(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        // Android's installer itself, so one tap installs; it asks about unknown apps if it needs to.
        val tap = PendingIntent.getActivity(
            context, 0, installIntent(context, r).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_davit)
            .setColor(SCARLET)
            .setContentTitle("daVIT ${r.version} is ready")
            .setContentText("Tap to install. Your data and sign-in stay as they are.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    private fun cancelNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION)
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!AutoUpdate.supported(applicationContext) || !AutoUpdate.enabled(applicationContext)) return Result.success()
        val before = AutoUpdate.ready.value?.version
        return try {
            val ready = AutoUpdate.check(applicationContext)
            Log.i("daVIT", "update check: " + (ready?.version ?: "up to date"))
            if (ready != null && ready.version != before) AutoUpdate.notifyReady(applicationContext, ready)
            Result.success()
        } catch (e: Exception) {
            Log.i("daVIT", "update check failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}
