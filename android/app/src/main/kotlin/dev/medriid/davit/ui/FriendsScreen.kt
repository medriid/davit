@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.medriid.davit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.SharedClass
import dev.medriid.davit.data.WeekCode
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.effectiveCalendar
import dev.medriid.davit.data.freeTogether
import dev.medriid.davit.data.shared
import dev.medriid.davit.data.teachingDays
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.printIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Swap weeks with friends by QR code (or a message) and see when you're all out of class,
 * and which block each of you is coming from. Only slots and blocks are shared, phone to
 * phone; nothing goes to a server.
 */
@Composable
internal fun FriendsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    if (scanning) {
        BackHandler { scanning = false }
        QrScanner(
            onCode = { code ->
                scanning = false
                note = if (repo.addFriend(code)) "Added ${WeekCode.decode(code)?.first}." else "That QR code isn't a daVIT week."
            },
            onClose = { scanning = false },
        )
        return
    }
    val s = state.snapshot
    val mine = s?.timetable.orEmpty().shared()
    val defaultName = s?.profile?.name?.substringBefore(' ')?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()
    val name = state.shareName.ifBlank { defaultName }
    val code = WeekCode.encode(name.ifBlank { "Friend" }, mine)

    RouteScreen(route, "Free time with friends", "Swap weeks by QR code and see when you're all out of class. Only your class slots and blocks are shared, phone to phone.") {
        // Free together first: it's what you come back for.
        val friends = state.friends
        var chosen by rememberSaveable { mutableStateOf<List<Long>?>(null) }
        if (friends.isNotEmpty()) {
            val with = friends.filter { chosen?.contains(it.id) ?: true }
            GroupLabel("Free together")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                friends.forEach { f ->
                    val on = f in with
                    Box(
                        Modifier.clickable(role = Role.Checkbox) {
                            val now = chosen ?: friends.map { it.id }
                            chosen = if (on) now - f.id else now + f.id
                        },
                    ) { InkChip(f.name, solid = on) }
                }
            }
            Spacer(Modifier.height(10.dp))
            val people = linkedMapOf<String, List<SharedClass>>("You" to mine).apply { with.forEach { put(it.name, it.classes) } }
            val today = remember { LocalDate.now() }
            val todayOrder = s?.effectiveCalendar()?.timetableDay(today) ?: today.dayOfWeek.value
            teachingDays(people.values).forEachIndexed { i, day ->
                val windows = freeTogether(people, day)
                InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).printIn(i), kind = Kind.People, plateOffset = 3.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkText(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.ENGLISH), style = RisoType.title, modifier = Modifier.weight(1f))
                        if (day == todayOrder) InkChip("Today", solid = true)
                    }
                    if (windows.isEmpty()) InkText("No time free together between 08:00 and 19:30.", style = RisoType.small, modifier = Modifier.padding(top = 4.dp))
                    windows.forEach { w ->
                        Column(Modifier.padding(top = 8.dp)) {
                            InkText("${clock(w.start)} – ${clock(w.end)} · ${length(w.end - w.start)}", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                            if (w.comingFrom.isNotEmpty()) {
                                InkText(w.comingFrom.entries.joinToString(" · ") { (who, block) -> "$who from ${block.ifBlank { "class" }}" }, style = RisoType.small)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        GroupLabel("Friends")
        note?.let { InkText(it, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 6.dp)) }
        if (friends.isEmpty()) InkText("Scan a friend's code, or paste one they sent you, to see when you're both free.", style = RisoType.small)
        friends.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    InkText(f.name, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText("${f.classes.size} class${if (f.classes.size == 1) "" else "es"} a week", style = RisoType.small)
                }
                Box(Modifier.clickable(role = Role.Button) { repo.removeFriend(f.id) }) { InkChip("Remove") }
            }
        }
        Spacer(Modifier.height(10.dp))
        InkButton("Scan a friend's code", { note = null; scanning = true }, fillWidth = true, modifier = Modifier.fillMaxWidth())
        var pasting by remember { mutableStateOf(false) }
        var pasted by remember { mutableStateOf("") }
        Spacer(Modifier.height(8.dp))
        if (!pasting) {
            InkButton("Paste a code they sent", { pasting = true }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
        } else {
            InkField(pasted, { pasted = it }, "Their message or code", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            InkButton("Add", {
                note = if (repo.addFriend(pasted)) "Added ${WeekCode.decode(pasted)?.first}." else "No daVIT week in that. It starts with DAVIT1:"
                if (note?.startsWith("Added") == true) { pasting = false; pasted = "" }
            }, fillWidth = true, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(22.dp))
        GroupLabel("Your code")
        if (mine.isEmpty()) {
            InkText("Your code shows here once your timetable has synced.", style = RisoType.small)
            return@RouteScreen
        }
        InkField(name, { repo.setShareName(it.take(30)) }, "Shown to friends as", modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrImage(code, 220.dp, description = "Your week as a QR code, for a friend to scan")
        }
        Spacer(Modifier.height(10.dp))
        InkText("${mine.size} classes: their slots and blocks, and the name above. Nothing else.", style = RisoType.small)
        Spacer(Modifier.height(10.dp))
        InkButton("Send it as a message", { shareText(context, "My week on daVIT", "My week on daVIT, for free time together. Paste this into daVIT → Free time with friends:\n\n$code") }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
    }
}

/** 140 → "2 h 20", 45 → "45 min". */
private fun length(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
