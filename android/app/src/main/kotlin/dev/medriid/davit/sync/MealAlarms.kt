package dev.medriid.davit.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.medriid.davit.DavitApp
import dev.medriid.davit.data.Alert
import dev.medriid.davit.data.AlertChannel
import dev.medriid.davit.data.mealReminders
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Meal reminders, the same way as [ClassAlarms]: the next few meals as alarms, set again
 * after each one fires, when the bell's choices change and after a reboot. Nothing is
 * fetched; the alarm only posts what the mess's sheet says.
 */
object MealAlarms {
    private const val ACTION = "dev.medriid.davit.MEAL_REMINDER"
    private const val PREFS = "davit.mealAlarms"
    private const val MAX = 8

    fun reschedule(context: Context) {
        val app = context.applicationContext as DavitApp
        val st = app.repo.state.value
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        repeat(store.getInt("count", 0)) { am.cancel(pending(context, it, null)) }
        val due = if (st.signedIn && st.alerts) mealReminders(st.alertPrefs, LocalDateTime.now()).take(MAX) else emptyList()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        due.forEachIndexed { i, r ->
            val at = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = pending(context, i, r.alert)
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Without exact alarms, a short window that ends at the reminder time.
                am.setWindow(AlarmManager.RTC_WAKEUP, at - 5 * 60_000L, 5 * 60_000L, pi)
            }
        }
        store.edit().putInt("count", due.size).apply()
    }

    private fun pending(context: Context, index: Int, alert: Alert?): PendingIntent {
        val intent = Intent(context, MealReminderReceiver::class.java).setAction(ACTION)
        alert?.let { intent.putExtra("key", it.key).putExtra("title", it.title).putExtra("text", it.text) }
        return PendingIntent.getBroadcast(context, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

class MealReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key")
        if (key != null) {
            val st = (context.applicationContext as DavitApp).repo.state.value
            if (st.signedIn && st.alerts) {
                Notifier.post(
                    context,
                    listOf(Alert(AlertChannel.Meals, key, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("text").orEmpty())),
                )
            }
        }
        // After a reminder, a reboot or a time change: set the next ones.
        MealAlarms.reschedule(context)
    }
}
