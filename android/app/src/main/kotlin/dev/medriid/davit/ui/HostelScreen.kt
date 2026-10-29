package dev.medriid.davit.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.vtop.Leave
import dev.medriid.davit.vtop.LeaveForm
import dev.medriid.davit.vtop.LeaveOutcome
import dev.medriid.davit.vtop.LeaveRequest
import dev.medriid.davit.vtop.NightSlip
import dev.medriid.davit.vtop.UserConsent
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hostel: your leave and night-slip requests (status and history), and a leave
 * application. The leave types come from VTOP's own form, read when you start one; the
 * application goes only after you've seen it whole and tapped Send.
 */
@Composable
internal fun HostelScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snap = state.snapshot
    val h = snap?.profile?.hostel
    val req = snap?.hostel
    RouteScreen(
        route, "Hostel",
        listOfNotNull(h?.block, h?.room?.ifBlank { null }?.let { "Room $it" }, h?.mess?.ifBlank { null }).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
        busy,
        {
            busy = true
            scope.launch {
                error = runCatching { repo.refreshHostel() }.exceptionOrNull()?.let(repo::explain)
                busy = false
            }
        },
    ) {
        Freshness(snap?.hostelAt ?: 0)
        error?.let { ErrorNote(it, null) }
        LeaveApplication()
        GroupLabel("Leave")
        RequestList(req?.activeLeave.orEmpty(), req?.pastLeave.orEmpty(), "No leave requests.") { l -> LeaveCard(l) }
        GroupLabel("Night slips")
        RequestList(req?.activeSlips.orEmpty(), req?.pastSlips.orEmpty(), "No night slips.") { s -> SlipCard(s) }
        Spacer(Modifier.height(10.dp))
        InkText("Night slips are applied for on VTOP.", style = RisoType.small.copy(color = LocalInks.current.rule))
    }
}

@Composable
private fun <T> RequestList(active: List<T>, past: List<T>, empty: String, card: @Composable (T) -> Unit) {
    if (active.isEmpty() && past.isEmpty()) {
        InkText(empty, style = RisoType.small)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        active.forEachIndexed { i, r -> Box(Modifier.printIn(i)) { card(r) } }
        if (past.isNotEmpty()) {
            InkText("History", label = true, modifier = Modifier.padding(top = 4.dp))
            past.reversed().take(12).forEach { card(it) }
        }
    }
}

@Composable
private fun LeaveCard(l: Leave) {
    InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(l.place.ifBlank { l.type }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText("${l.from} to ${l.to}", style = RisoType.small)
                InkText(listOf(l.type, l.reason).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            InkChip(l.status.ifBlank { "–" }, solid = l.status.contains("approv", ignoreCase = true))
        }
    }
}

@Composable
private fun SlipCard(s: NightSlip) {
    InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText(s.venue.ifBlank { s.purpose }, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                InkText(listOf(s.from, s.time).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                if (s.purpose.isNotBlank()) InkText(s.purpose, style = RisoType.small, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            InkChip(s.status.ifBlank { "–" }, solid = s.status.contains("approv", ignoreCase = true))
        }
    }
}

private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)

/** Start → VTOP's form (types read live) → review → Send. Nothing goes to VTOP before Send. */
@Composable
private fun LeaveApplication() {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var form by remember { mutableStateOf<LeaveForm?>(null) }
    var opening by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<LeaveOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var type by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDateTime.now().plusDays(2).withHour(18).withMinute(0).withSecond(0).withNano(0).toString()) }
    val fromAt = LocalDateTime.parse(from)
    val toAt = LocalDateTime.parse(to)

    GroupLabel("Apply for leave")
    outcome?.let { o ->
        InkCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), plate = if (o.ok) LocalInks.current.b else LocalInks.current.a, plateOffset = 4.dp) {
            InkText(if (o.ok) "Sent" else "VTOP said", label = true)
            Spacer(Modifier.height(6.dp))
            InkText(o.message)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.clickable(role = Role.Button) { outcome = null }) { InkChip("OK") }
        }
    }
    val f = form
    if (f == null) {
        InkText("daVIT reads VTOP's leave form (its leave types and your proctor) when you start, and sends nothing until you review it and tap Send.", style = RisoType.small)
        Spacer(Modifier.height(10.dp))
        error?.let { ErrorNote(it, null) }
        InkButton(if (opening) "Opening VTOP's form" else "Start an application", {
            if (opening) return@InkButton
            opening = true
            error = null
            scope.launch {
                runCatching { repo.onVtop { c -> c.leaveForm() } }
                    .onSuccess {
                        if (it == null || it.types.isEmpty()) error = "VTOP didn't show a leave form (it's for hostellers, and closes at times)."
                        else {
                            form = it
                            if (type !in it.types.map { t -> t.first }) type = ""
                        }
                    }
                    .onFailure { error = repo.explain(it) }
                opening = false
            }
        }, enabled = !opening, fillWidth = true, modifier = Modifier.fillMaxWidth())
        return
    }

    val problems = listOfNotNull(
        "Pick a leave type.".takeIf { type.isEmpty() },
        "Say where you're going.".takeIf { place.isBlank() },
        "Give a reason.".takeIf { reason.isBlank() },
        "Leave can't start in the past.".takeIf { fromAt.isBefore(LocalDateTime.now().minusMinutes(5)) },
        "Return has to be after leaving.".takeIf { !toAt.isAfter(fromAt) },
    )
    InkCard(Modifier.fillMaxWidth(), plateOffset = 4.dp) {
        if (f.proctor.isNotBlank()) InkText("Goes to your proctor, ${titleCase(f.proctor)}", style = RisoType.small)
        Spacer(Modifier.height(10.dp))
        InkText("Leave type", label = true)
        Spacer(Modifier.height(4.dp))
        ChoiceRow(f.types.map { it.second.lowercase().replaceFirstChar { c -> c.uppercase() } }, f.types.indexOfFirst { it.first == type }) {
            type = f.types[it].first
            reviewing = false
        }
        Spacer(Modifier.height(10.dp))
        InkField(place, { place = it.take(80); reviewing = false }, "Visiting place", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        WhenRow("Leaving", fromAt) { picked -> from = picked.toString(); reviewing = false }
        Spacer(Modifier.height(8.dp))
        WhenRow("Back", toAt) { picked -> to = picked.toString(); reviewing = false }
        Spacer(Modifier.height(12.dp))
        InkField(reason, { reason = it.take(200); reviewing = false }, "Reason", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        if (problems.isNotEmpty()) InkText(problems.first(), style = RisoType.small.copy(color = LocalInks.current.a))
        AnimatedVisibility(!reviewing) {
            Column {
                Spacer(Modifier.height(8.dp))
                InkButton("Review", { reviewing = true; haptics.tick() }, enabled = problems.isEmpty(), fillWidth = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                InkButton("Discard", { form = null; reviewing = false }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
        }
        AnimatedVisibility(reviewing && problems.isEmpty()) {
            Column {
                Spacer(Modifier.height(4.dp))
                Rule()
                Spacer(Modifier.height(10.dp))
                InkText("Send this to VTOP?", label = true)
                Spacer(Modifier.height(6.dp))
                InkText(f.types.firstOrNull { it.first == type }?.second.orEmpty() + " · " + place.trim(), style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                InkText("${fromAt.format(dayTime)} to ${toAt.format(dayTime)}", style = RisoType.small)
                InkText(reason.trim(), style = RisoType.small)
                Spacer(Modifier.height(12.dp))
                InkButton(if (sending) "Sending" else "Send to VTOP", {
                    if (sending) return@InkButton
                    sending = true
                    haptics.heavy()
                    val request = LeaveRequest(type, place, fromAt, toAt, reason)
                    scope.launch {
                        runCatching {
                            repo.onVtop { c ->
                                // A fresh read of the form, for the current token.
                                val live = c.leaveForm() ?: throw dev.medriid.davit.vtop.Refused("VTOP's leave form closed.")
                                c.applyLeave(UserConsent("hostel leave"), live, request)
                            }
                        }.onSuccess {
                            outcome = it
                            if (it.ok) {
                                haptics.confirm()
                                form = null
                                reviewing = false
                                place = ""
                                reason = ""
                                runCatching { repo.refreshHostel() }
                            } else {
                                haptics.reject()
                            }
                        }.onFailure {
                            haptics.reject()
                            outcome = LeaveOutcome(false, repo.explain(it))
                        }
                        sending = false
                    }
                }, enabled = !sending, fillWidth = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                InkButton("Edit", { reviewing = false }, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A date and time, each opening the system picker. */
@Composable
private fun WhenRow(label: String, at: LocalDateTime, onPick: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        InkText(label, label = true, modifier = Modifier.width(70.dp))
        Box(Modifier.clickable(role = Role.Button) {
            DatePickerDialog(context, { _, y, m, d -> onPick(at.withYear(y).withMonth(m + 1).withDayOfMonth(d)) }, at.year, at.monthValue - 1, at.dayOfMonth).show()
        }.padding(end = 8.dp)) { InkChip(at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), solid = true) }
        Box(Modifier.clickable(role = Role.Button) {
            TimePickerDialog(context, { _, hh, mm -> onPick(at.withHour(hh).withMinute(mm)) }, at.hour, at.minute, false).show()
        }) { InkChip(at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))) }
    }
}
