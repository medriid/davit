package dev.medriid.davit.widget

import android.content.Context
import android.content.res.Configuration
import androidx.glance.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.medriid.davit.DavitApp
import dev.medriid.davit.MainActivity
import dev.medriid.davit.data.UiState
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.planFor
import dev.medriid.davit.data.remainingFor
import dev.medriid.davit.data.vtopDate
import dev.medriid.davit.data.weekOf
import dev.medriid.davit.design.InkPairs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Home-screen widget: the class on now (or next), its attendance, and the nearest thing
 * due. Paper and ink from your ink pair. Redrawn after every sync and every 30 minutes.
 */
class DavitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = (context.applicationContext as DavitApp).repo.state.value
        provideContent { Content(state, LocalDateTime.now()) }
    }

    companion object {
        /** Redraws every placed widget; safe to call from anywhere. */
        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.Default).launch { runCatching { DavitWidget().updateAll(context.applicationContext) } }
        }
    }
}

class DavitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DavitWidget()
}

@Composable
private fun Content(s: UiState, now: LocalDateTime) {
    val context = LocalContext.current
    val systemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val inks = InkPairs.of(s.inkIndex, s.theme == 2 || (s.theme == 0 && systemNight))
    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(22.dp)
            .background(inks.paper)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text("daVIT", style = style(inks.b, 14.sp, bold = true))
            Spacer(GlanceModifier.defaultWeight())
            s.snapshot?.let { Text(ago(it.syncedAt), style = style(inks.text.copy(alpha = 0.7f), 11.sp)) }
        }
        Spacer(GlanceModifier.height(6.dp))
        val snap = s.snapshot
        when {
            !s.signedIn -> Text("Sign in to daVIT to see your classes.", style = style(inks.text, 13.sp))
            snap == null -> Text("Waiting for the first sync.", style = style(inks.text, 13.sp))
            else -> {
                val plan = planFor(weekOf(snap), now, snap.calendar)
                val slot = plan.now ?: plan.next
                if (slot == null) {
                    Text("No classes in your timetable.", style = style(inks.text, 13.sp))
                } else {
                    val m = slot.meeting
                    val label = if (plan.now != null) "NOW · until ${clock(m.end)}" else "NEXT · " + when (plan.nextInDays) {
                        0 -> clock(m.start)
                        1 -> "tomorrow ${clock(m.start)}"
                        else -> (plan.nextDate?.dayOfWeek ?: DayOfWeek.of(m.day)).getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH) + " " + clock(m.start)
                    }
                    Text(label, style = style(inks.a, 11.sp, bold = true))
                    Text(slot.course.courseName, style = style(inks.text, 16.sp, bold = true), maxLines = 1)
                    Text(
                        listOf(slot.course.venue, if (slot.isLab) "Lab" else "Theory").filter { it.isNotBlank() }.joinToString(" · "),
                        style = style(inks.text, 12.sp),
                        maxLines = 1,
                    )
                    slot.attendance?.let { row ->
                        Text(
                            "${row.percent}% · ${row.hint(snap.remainingFor(row, now))}",
                            style = style(if (row.atRisk && !snap.attendanceExempt) inks.a else inks.b, 12.sp, bold = true),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(footer(s, now), style = style(inks.text.copy(alpha = 0.75f), 11.sp), maxLines = 1)
            }
        }
    }
}

/** The nearest unsubmitted assignment within two weeks, else overall attendance. */
private fun footer(s: UiState, now: LocalDateTime): String {
    val snap = s.snapshot ?: return ""
    val today = now.toLocalDate()
    val due = snap.assignments.flatMap { it.items }
        .filter { it.updated == null }
        .mapNotNull { a -> vtopDate(a.due)?.let { a to it } }
        .filter { (_, d) -> !d.isBefore(today) && d.toEpochDay() - today.toEpochDay() <= 14 }
        .minByOrNull { it.second }
    if (due != null) {
        val days = due.second.toEpochDay() - today.toEpochDay()
        val whenText = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in $days days"
        }
        return "Due $whenText · ${due.first.title}"
    }
    val att = snap.attendance
    val total = att.sumOf { it.total }
    val risky = if (snap.attendanceExempt) 0 else att.count { it.atRisk }
    return (if (total == 0) "" else "${att.sumOf { it.attended } * 100 / total}% overall · ") + "$risky at risk"
}

private fun ago(at: Long): String {
    val min = (System.currentTimeMillis() - at) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} d ago"
    }
}

private fun style(color: Color, size: TextUnit, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
