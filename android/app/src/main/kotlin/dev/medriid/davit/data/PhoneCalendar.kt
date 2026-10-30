package dev.medriid.davit.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Your classes in the phone's calendar: a calendar of daVIT's own ("daVIT classes", on
 * this phone only, never synced to an account), so updating it can clear what's stale.
 * Classes already past stay as a record; everything from now on is rewritten after each
 * sync while it's on. Signing out deletes the calendar.
 */
object PhoneCalendar {
    private const val ACCOUNT = "daVIT"
    private const val PREFS = "davit.calendar"
    private const val K_ON = "on"

    fun permitted(context: Context): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, false)

    private fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun calendarId(context: Context): Long? = context.contentResolver.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun ensureCalendar(context: Context, color: Int): Long = calendarId(context) ?: run {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "davit-classes")
            put(Calendars.CALENDAR_DISPLAY_NAME, "daVIT classes")
            put(Calendars.CALENDAR_COLOR, color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, CampusZone.id)
        }
        val uri = context.contentResolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values) ?: error("The phone's calendar didn't take a new calendar")
        uri.lastPathSegment!!.toLong()
    }

    /** Turns it on and writes [events]; returns how many went in. Needs the calendar permissions. */
    suspend fun turnOn(context: Context, events: List<ClassEvent>, color: Int): Int {
        setEnabled(context, true)
        return replace(context, events, color)
    }

    /** After a sync, while it's on: the classes from now rewritten. */
    suspend fun refresh(context: Context, events: List<ClassEvent>, color: Int) {
        if (!enabled(context) || !permitted(context)) return
        runCatching { replace(context, events, color) }.onFailure { Log.w("daVIT", "calendar update failed: ${it.javaClass.simpleName}") }
    }

    /** Off: the calendar and its events go. */
    suspend fun turnOff(context: Context) = withContext(Dispatchers.IO) {
        setEnabled(context, false)
        if (!permitted(context)) return@withContext
        calendarId(context)?.let { id ->
            runCatching { context.contentResolver.delete(asSyncAdapter(Calendars.CONTENT_URI), "${Calendars._ID} = ?", arrayOf(id.toString())) }
        }
    }

    private suspend fun replace(context: Context, events: List<ClassEvent>, color: Int): Int = withContext(Dispatchers.IO) {
        val id = ensureCalendar(context, color)
        val now = System.currentTimeMillis()
        val resolver = context.contentResolver
        resolver.delete(asSyncAdapter(Events.CONTENT_URI), "${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} >= ?", arrayOf(id.toString(), now.toString()))
        val ops = events.filter { it.startAt.toInstant().toEpochMilli() >= now }.map { e ->
            ContentProviderOperation.newInsert(asSyncAdapter(Events.CONTENT_URI))
                .withValue(Events.CALENDAR_ID, id)
                .withValue(Events.TITLE, e.title)
                .withValue(Events.EVENT_LOCATION, e.location)
                .withValue(Events.DESCRIPTION, e.notes)
                .withValue(Events.DTSTART, e.startAt.toInstant().toEpochMilli())
                .withValue(Events.DTEND, e.endAt.toInstant().toEpochMilli())
                .withValue(Events.EVENT_TIMEZONE, CampusZone.id)
                .withValue(Events.UID_2445, e.uid)
                .withValue(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                .build()
        }
        ops.chunked(100).forEach { resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(it)) }
        ops.size
    }
}
