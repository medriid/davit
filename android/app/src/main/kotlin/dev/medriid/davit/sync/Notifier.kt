package dev.medriid.davit.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.medriid.davit.MainActivity
import dev.medriid.davit.R
import dev.medriid.davit.data.Alert
import dev.medriid.davit.data.AlertChannel

/**
 * Posts [Alert]s, each at most once: keys already sent are remembered (the last few
 * hundred). Three or more in one channel collapse into a single summary.
 */
object Notifier {
    private const val PREFS = "davit.alerts"
    private const val K_SENT = "sent"
    private const val KEEP = 400
    private const val SCARLET = 0xFFF65058.toInt()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        AlertChannel.entries.forEach {
            nm.createNotificationChannel(NotificationChannel(it.id, it.title, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun post(context: Context, alerts: List<Alert>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getString(K_SENT, "").orEmpty().lines().filter { it.isNotEmpty() }
        val fresh = alerts.filter { it.key !in sent }.distinctBy { it.key }
        if (fresh.isEmpty() || !permitted(context)) return
        val nm = NotificationManagerCompat.from(context)
        fresh.groupBy { it.channel }.forEach { (channel, list) ->
            try {
                if (list.size >= 3) {
                    val style = NotificationCompat.InboxStyle()
                    list.forEach { style.addLine("${it.title} · ${it.text}") }
                    nm.notify(channel.id.hashCode(), builder(context, channel, "${list.size} updates", list.first().title).setStyle(style).build())
                } else {
                    list.forEach {
                        nm.notify(it.key.hashCode(), builder(context, channel, it.title, it.text)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(it.text)).build())
                    }
                }
            } catch (_: SecurityException) {
                // Permission revoked between the check and the post; nothing to do.
            }
        }
        prefs.edit().putString(K_SENT, (sent + fresh.map { it.key }).takeLast(KEEP).joinToString("\n")).apply()
    }

    /** Sign-out: forget what was sent, so a new account starts clean. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun builder(context: Context, channel: AlertChannel, title: String, text: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_davit)
            .setColor(SCARLET)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
    }
}
