package dev.medriid.davit.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import dev.medriid.davit.DavitApp
import dev.medriid.davit.MainActivity
import dev.medriid.davit.data.UiState
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.hint
import dev.medriid.davit.data.isLabType
import dev.medriid.davit.data.percent
import dev.medriid.davit.data.remainingFor
import dev.medriid.davit.data.watchList
import java.time.LocalDateTime

/**
 * Home-screen widget: the courses at or near the line (your own warning line, or a couple
 * of skips from it), worst first, each with what it takes. As many as fit.
 */
class AttendanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = (context.applicationContext as DavitApp).repo.state.value
        provideContent { Content(state, LocalDateTime.now()) }
    }
}

class AttendanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AttendanceWidget()
}

@Composable
private fun Content(s: UiState, now: LocalDateTime) {
    val context = LocalContext.current
    val systemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val inks = s.palette.inks(s.theme == 2 || (s.theme == 0 && systemNight))
    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(22.dp)
            .background(inks.paper)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text("Attendance", style = style(inks.b, 14.sp, bold = true))
            Spacer(GlanceModifier.defaultWeight())
            s.snapshot?.let { Text(ago(it.syncedAt), style = style(inks.text.copy(alpha = 0.7f), 11.sp)) }
        }
        Spacer(GlanceModifier.height(6.dp))
        val snap = s.snapshot
        when {
            !s.signedIn -> Text("Sign in to daVIT to see your attendance.", style = style(inks.text, 13.sp))
            snap == null -> Text("Waiting for the first sync.", style = style(inks.text, 13.sp))
            snap.attendanceExempt -> Text("CGPA 9 or above: the 75% rule doesn't apply to you.", style = style(inks.text, 13.sp))
            else -> {
                val watch = snap.watchList(s.alertPrefs)
                if (watch.isEmpty()) {
                    val lowest = snap.attendance.minByOrNull { it.percent }
                    Text("All clear of ${s.alertPrefs.warnBelow}%.", style = style(inks.b, 14.sp, bold = true))
                    lowest?.let { Text("Lowest: ${it.courseName} at ${it.percent}%", style = style(inks.text, 12.sp), maxLines = 1) }
                } else {
                    // About 40 dp per course after the header.
                    val fits = ((LocalSize.current.height.value - 44f) / 40f).toInt().coerceAtLeast(1)
                    watch.take(fits).forEach { row ->
                        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Text("${row.percent}%", style = style(if (row.atRisk) inks.a else inks.b, 15.sp, bold = true))
                            Spacer(GlanceModifier.width(8.dp))
                            Column(GlanceModifier.defaultWeight()) {
                                Text(
                                    row.courseName + if (isLabType(row.classType)) " · Lab" else "",
                                    style = style(inks.text, 12.sp, bold = true), maxLines = 1,
                                )
                                Text(row.hint(snap.remainingFor(row, now)), style = style(inks.text, 11.sp), maxLines = 1)
                            }
                        }
                    }
                    if (watch.size > fits) Text("+${watch.size - fits} more", style = style(inks.text.copy(alpha = 0.7f), 11.sp))
                }
            }
        }
    }
}
