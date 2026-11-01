package dev.medriid.davit.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.AlertPrefs
import dev.medriid.davit.data.Meal
import dev.medriid.davit.data.Mess
import dev.medriid.davit.data.MessDay
import dev.medriid.davit.data.Messes
import dev.medriid.davit.data.label
import dev.medriid.davit.data.messDayLabel
import dev.medriid.davit.data.on
import dev.medriid.davit.data.startLabel
import dev.medriid.davit.design.EditorialShape
import dev.medriid.davit.design.IconGlyph
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoIcon
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import dev.medriid.davit.sync.Notifier
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MessPanel { None, Mess, Calendar, Bell }

/**
 * What the mess serves, a day at a time, from its printed sheet. Opens on today; a day the
 * sheet doesn't cover says so rather than guessing. The bell sets a reminder per meal.
 */
@Composable
internal fun MessMenuScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val prefs = state.alertPrefs
    val mess = Messes.of(prefs.mess)
    val today = remember { LocalDate.now() }
    var day by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var panel by rememberSaveable { mutableStateOf(MessPanel.None) }
    val date = LocalDate.ofEpochDay(day)
    fun toggle(p: MessPanel) { panel = if (panel == p) MessPanel.None else p }

    RouteScreen(route, "Mess menu") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { toggle(MessPanel.Mess) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkText("Menu for ", style = RisoType.body)
                InkText(mess.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                InkText(if (panel == MessPanel.Mess) "▴" else "▾", style = RisoType.small)
            }
            Spacer(Modifier.width(8.dp))
            MessButton(RisoIcon.Bell, "Meal reminders", on = prefs.meals.isNotEmpty()) { toggle(MessPanel.Bell) }
        }
        if (panel == MessPanel.Mess) {
            MessPicker(mess) { picked ->
                repo.setAlertPrefs(prefs.copy(mess = picked.id))
                panel = MessPanel.None
            }
        }
        if (panel == MessPanel.Bell) MealBell(mess, prefs, repo::setAlertPrefs)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(messDayLabel(date), style = RisoType.title.copy(fontSize = 22.sp), maxLines = 1)
                InkText(
                    when (date) {
                        today -> "Today"
                        today.plusDays(1) -> "Tomorrow"
                        today.minusDays(1) -> "Yesterday"
                        else -> date.year.toString()
                    },
                    style = RisoType.small,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MessButton(RisoIcon.Today, "Pick a date", on = panel == MessPanel.Calendar) { toggle(MessPanel.Calendar) }
                MessArrow("Previous day", forward = false) { day-- }
                MessArrow("Next day", forward = true) { day++ }
            }
        }
        if (date != today) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .clickable(role = Role.Button) { day = today.toEpochDay() }
                    .padding(vertical = 4.dp),
            ) { InkChip("Back to today") }
        }
        if (panel == MessPanel.Calendar) {
            MessCalendar(mess, date, today) {
                day = it.toEpochDay()
                panel = MessPanel.None
            }
        }

        Spacer(Modifier.height(14.dp))
        when (val menu = mess.on(date)) {
            is MessDay.Menu -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meal.entries.forEachIndexed { i, meal ->
                        MealCard(mess, meal, menu.meals[meal].orEmpty(), reminded = meal.name in prefs.meals, Modifier.printIn(i))
                    }
                }
                mess.timesNote?.let {
                    InkText(it, style = RisoType.small, modifier = Modifier.padding(top = 12.dp))
                }
            }
            is MessDay.NotOnSheet -> InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
                InkText("Not on this month's sheet", style = RisoType.title.copy(fontSize = 17.sp))
                Spacer(Modifier.height(6.dp))
                InkText("The ${monthLabel(menu.month)} sheet has no row for ${messDayLabel(date)}, so there's nothing to show for it.", style = RisoType.small)
            }
            MessDay.NoSheet -> InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
                InkText("No menu for ${monthLabel(YearMonth.from(date))} yet", style = RisoType.title.copy(fontSize = 17.sp))
                Spacer(Modifier.height(6.dp))
                InkText("daVIT has the ${mess.name} sheet for ${mess.sheets.joinToString(", ") { monthLabel(it.month) }}.", style = RisoType.small)
            }
        }
    }
}

private fun monthLabel(month: YearMonth) = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))

@Composable
private fun MealCard(mess: Mess, meal: Meal, items: List<String>, reminded: Boolean, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    val time = mess.times[meal]
    InkCard(modifier.fillMaxWidth(), plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(meal.title, style = RisoType.title.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
            if (reminded && time != null) {
                IconGlyph(RisoIcon.Bell, inks.a, Modifier.size(14.dp).semantics { contentDescription = "Reminder on" })
                Spacer(Modifier.width(6.dp))
            }
            InkText(time?.label() ?: "Time not known yet", style = RisoType.small, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        InkText(items.joinToString(", "))
    }
}

/** A 44 dp icon button, built like [BackButton] in each style. */
@Composable
private fun MessButton(icon: RisoIcon, label: String, on: Boolean = false, onClick: () -> Unit) {
    MessButtonFrame(label, onClick) { IconGlyph(icon, if (on) LocalInks.current.a else LocalInks.current.text, Modifier.size(20.dp)) }
}

/** A day or a month back or on: a plain chevron, the same as [BackButton]'s. */
@Composable
private fun MessArrow(label: String, forward: Boolean, onClick: () -> Unit) {
    val ink = LocalInks.current.text
    MessButtonFrame(label, onClick) {
        Canvas(Modifier.size(18.dp).graphicsLayer { scaleX = if (forward) -1f else 1f }) {
            val w = 2.4.dp.toPx()
            drawLine(ink, Offset(size.width * 0.62f, size.height * 0.18f), Offset(size.width * 0.3f, size.height * 0.5f), w, StrokeCap.Round)
            drawLine(ink, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.82f), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun MessButtonFrame(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val inks = LocalInks.current
    val shape = if (inks.editorial) EditorialShape else CircleShape
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .border(
                if (inks.soft || inks.editorial) 1.dp else 2.dp,
                when {
                    inks.editorial -> inks.rule
                    inks.soft -> inks.text.copy(alpha = 0.25f)
                    else -> inks.text
                },
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Which mess to read. One for now; the list grows with [Messes.all]. */
@Composable
private fun MessPicker(current: Mess, onPick: (Mess) -> Unit) {
    InkCard(Modifier.fillMaxWidth().padding(top = 8.dp), plateOffset = 3.dp) {
        Messes.all.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) { onPick(m) }
                    .semantics { selected = m.id == current.id }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    InkText(m.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText(m.hostel, style = RisoType.small)
                }
                if (m.id == current.id) InkChip("Showing", solid = true)
            }
        }
    }
}

/** The bell: a switch per meal, how early, and what to do if notifications are off. */
@Composable
private fun MealBell(mess: Mess, prefs: AlertPrefs, set: (AlertPrefs) -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(Notifier.permitted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = Notifier.permitted(context) }
    InkCard(Modifier.fillMaxWidth().padding(top = 8.dp), plateOffset = 3.dp) {
        InkText("Remind me", style = RisoType.title.copy(fontSize = 17.sp))
        Spacer(Modifier.height(4.dp))
        Meal.entries.forEach { meal ->
            val time = mess.times[meal]
            val on = meal.name in prefs.meals
            if (time == null) {
                InkText(meal.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                InkText("No reminder until its time is known.", style = RisoType.small, modifier = Modifier.padding(bottom = 8.dp))
            } else {
                ToggleRow(meal.title, "Starts at ${time.startLabel()}", on) {
                    set(prefs.copy(meals = if (on) prefs.meals - meal.name else prefs.meals + meal.name))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        InkText("How early", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(6.dp))
        ChoiceRow(AlertPrefs.BeforeMealOptions.map { if (it == 0) "When it starts" else "$it min" }, AlertPrefs.BeforeMealOptions.indexOf(prefs.beforeMeal).coerceAtLeast(0)) {
            set(prefs.copy(beforeMeal = AlertPrefs.BeforeMealOptions[it]))
        }
        if (prefs.meals.isNotEmpty() && (!state.alerts || !permitted)) {
            Spacer(Modifier.height(8.dp))
            InkText(
                if (!state.alerts) "Notifications are off in daVIT, so these won't reach you." else "Android is blocking daVIT's notifications, so these won't reach you.",
                style = RisoType.small,
            )
            Spacer(Modifier.height(8.dp))
            InkButton("Turn on", {
                repo.setAlerts(true)
                if (!permitted) {
                    if (Build.VERSION.SDK_INT >= 33 && !repo.askedForNotifications) {
                        repo.askedForNotifications = true
                        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            }, quiet = true)
        }
    }
}

/** A month to jump around in. Faded days aren't on the sheet; they can still be opened, to say so. */
@Composable
private fun MessCalendar(mess: Mess, date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val inks = LocalInks.current
    var shown by rememberSaveable(date.toEpochDay()) { mutableLongStateOf(date.withDayOfMonth(1).toEpochDay()) }
    val month = YearMonth.from(LocalDate.ofEpochDay(shown))
    val shape = when {
        inks.editorial -> EditorialShape
        inks.soft -> CircleShape
        else -> RoundedCornerShape(6.dp)
    }
    InkCard(Modifier.fillMaxWidth().padding(top = 10.dp), plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText(monthLabel(month), style = RisoType.title.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
            MessArrow("Previous month", forward = false) { shown = month.minusMonths(1).atDay(1).toEpochDay() }
            Spacer(Modifier.width(8.dp))
            MessArrow("Next month", forward = true) { shown = month.plusMonths(1).atDay(1).toEpochDay() }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { InkText(it, label = true) } }
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        for (wk in 0 until (lead + month.lengthOfMonth() + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = wk * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1.3f).padding(2.dp)) {
                        if (n in 1..month.lengthOfMonth()) {
                            val d = month.atDay(n)
                            val on = d == date
                            val hasMenu = mess.on(d) is MessDay.Menu
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(shape)
                                    .background(if (on) inks.a else Color.Transparent)
                                    .then(if (d == today) Modifier.border(2.dp, inks.text, shape) else Modifier)
                                    .clickable(role = Role.Button) { onPick(d) }
                                    .semantics { selected = on },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    "$n",
                                    style = RisoType.small.copy(
                                        color = when {
                                            on -> inks.paper
                                            hasMenu -> inks.text
                                            else -> inks.muted.copy(alpha = 0.45f)
                                        },
                                        fontWeight = if (hasMenu) FontWeight.SemiBold else FontWeight.Normal,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        InkText("Faded days aren't on the sheet.", style = RisoType.small)
    }
}
