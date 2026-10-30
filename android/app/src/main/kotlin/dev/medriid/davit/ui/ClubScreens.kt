package dev.medriid.davit.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Section
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Rule
import dev.medriid.davit.design.printIn
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.vtop.ClubListing
import dev.medriid.davit.vtop.Reply
import dev.medriid.davit.vtop.UserConsent
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Club and chapter events from VTOP's Event Registration page: yours (with certificates),
 * the ones open for registration, and the rest. Registering takes a tap and a confirm.
 */
@Composable
internal fun EventsScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val campus by repo.campus.state.collectAsState()
    val loading by repo.campus.loading.collectAsState()
    val errors by repo.campus.errors.collectAsState()
    var reply by remember { mutableStateOf<Reply?>(null) }
    LaunchedEffect(Unit) { repo.campus.refresh(Section.Clubs) }
    val clubs = campus.clubs
    RouteScreen(
        route, "Club events", "Registering goes to VTOP only after you confirm it.",
        Section.Clubs in loading && campus.clubsAt > 0, { repo.campus.refresh(Section.Clubs, true) },
    ) {
        Freshness(campus.clubsAt)
        errors[Section.Clubs]?.let { ErrorNote(it) { repo.campus.refresh(Section.Clubs, true) } }
        reply?.let { ReplyCard(it) { reply = null } }
        if (clubs == null) {
            if (Section.Clubs in loading) Loading("Reading VTOP's event registration") else HomePageEvents(state.snapshot?.feeds?.events.orEmpty())
            return@RouteScreen
        }

        if (clubs.registered.isNotEmpty()) {
            GroupLabel("Registered")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                clubs.registered.forEachIndexed { i, e ->
                    InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.Events, plateOffset = 3.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                InkText(e.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                                InkText(listOf(clubName(e.club), eventDays(e.dates)).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 2)
                            }
                            if (e.status.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                InkChip(sentence(e.status))
                            }
                        }
                        if (e.participation) FileRow("cert:p:${e.id}", "Participation certificate", null, fetch = { c, out, p -> c.downloadEventCertificate(e.id, false, out, p) })
                        if (e.winner) FileRow("cert:w:${e.id}", "Winner certificate", null, fetch = { c, out, p -> c.downloadEventCertificate(e.id, true, out, p) })
                    }
                }
            }
        }

        val open = clubs.upcoming.filter { it.canRegister }
        GroupLabel("Open for registration")
        if (open.isEmpty()) InkText("No club event is taking registrations right now.", style = RisoType.small)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            open.forEachIndexed { i, e -> ListingCard(e, Modifier.printIn(i)) { reply = it } }
        }

        val shut = clubs.upcoming.filter { !it.canRegister }
        if (shut.isNotEmpty()) {
            GroupLabel("Registration over")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Events, plateOffset = 3.dp) {
                shut.forEachIndexed { i, e ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            InkText(e.title, style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            InkText(listOf(clubName(e.club), eventDays(e.dates)).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        InkChip(sentence(e.state.ifBlank { "Closed" }))
                    }
                }
            }
        }

        if (clubs.board.isNotEmpty() || campus.generalCertificates.isNotEmpty()) {
            GroupLabel("Certificates")
            InkCard(Modifier.fillMaxWidth(), kind = dev.medriid.davit.design.Kind.Events, plateOffset = 3.dp) {
                clubs.board.forEachIndexed { i, b ->
                    if (i > 0) Rule()
                    FileRow("cert:b:${b.associationId}:${b.generatedYear}:${b.roleId}", "${sentence(b.position)}, ${clubName(b.club)}", b.year,
                        fetch = { c, out, p -> c.downloadBoardCertificate(b, out, p) })
                }
                campus.generalCertificates.forEachIndexed { i, g ->
                    if (i > 0 || clubs.board.isNotEmpty()) Rule()
                    FileRow("cert:g:${g.eventId}:${g.certificateId}", g.name, "General event", fetch = { c, out, p -> c.downloadGeneralCertificate(g, out, p) })
                }
            }
        }
    }
}

/** One event open for registration: tap for its description; Register asks once more before it goes. */
@Composable
private fun ListingCard(e: ClubListing, modifier: Modifier, onReply: (Reply) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var more by remember(e.id) { mutableStateOf(false) }
    var asking by remember(e.id) { mutableStateOf(false) }
    var sending by remember(e.id) { mutableStateOf(false) }
    InkCard(modifier.fillMaxWidth(), onClick = { more = !more }, kind = dev.medriid.davit.design.Kind.Events, plateOffset = 3.dp) {
        InkText(e.title, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 3)
        InkText(clubName(e.club), style = RisoType.small, maxLines = 2)
        InkText(listOf(eventDays(e.dates), e.time).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
        if (e.venue.isNotBlank()) InkText(e.venue, style = RisoType.small)
        registrationEnds(e.registration)?.let { InkText("Registration till $it", style = RisoType.small.copy(color = LocalInks.current.rule)) }
        if (more && e.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            InkText(e.description, style = RisoType.small)
        }
        Spacer(Modifier.height(10.dp))
        if (!asking) {
            Box(Modifier.clickable(role = Role.Button) { asking = true; haptics.tick() }) { InkChip("Register", solid = true) }
        }
        AnimatedVisibility(asking) {
            Column {
                Rule()
                Spacer(Modifier.height(8.dp))
                InkText("Register for this on VTOP?", label = true)
                Spacer(Modifier.height(10.dp))
                InkButton(if (sending) "Registering" else "Register on VTOP", {
                    if (sending) return@InkButton
                    sending = true
                    haptics.heavy()
                    scope.launch {
                        runCatching { repo.onVtop { c -> c.registerEvent(UserConsent("club event ${e.id}"), e.id) } }
                            .onSuccess {
                                onReply(it)
                                if (it.ok) haptics.confirm() else haptics.reject()
                                asking = false
                                repo.campus.refresh(Section.Clubs, true)
                            }
                            .onFailure { haptics.reject(); onReply(Reply(false, repo.explain(it))) }
                        sending = false
                    }
                }, enabled = !sending, fillWidth = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                InkButton("Not now", { asking = false }, enabled = !sending, quiet = true, fillWidth = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The home page's event list, when the registration page couldn't be read. */
@Composable
private fun HomePageEvents(events: List<dev.medriid.davit.vtop.ClubEvent>) {
    if (events.isEmpty()) {
        InkText("No events listed. Pull down to check again.")
        return
    }
    val today = LocalDate.now()
    val f = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    events.groupBy { it.date }.toSortedMap(compareBy(nullsLast()) { it }).forEach { (day, list) ->
        GroupLabel(
            when {
                day == null -> "Coming up"
                day == today -> "Today"
                day == today.plusDays(1) -> "Tomorrow"
                else -> day.format(f)
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEachIndexed { i, e ->
                InkCard(Modifier.fillMaxWidth().printIn(i), kind = dev.medriid.davit.design.Kind.Events, plateOffset = 3.dp) {
                    InkText(e.title, style = RisoType.title.copy(fontSize = 17.sp), maxLines = 3)
                    InkText(listOf(e.category, e.club).filter { it.isNotBlank() }.joinToString(" · "), style = RisoType.small)
                }
            }
        }
    }
}

/** "ROBOTICS CLUB (RC) (CLUB)" → "Robotics Club (RC)". */
internal fun clubName(s: String): String =
    s.replace(Regex("""\s*\((CLUB|CHAPTER|SOCIETY)\)\s*$""", RegexOption.IGNORE_CASE), "").split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
        if (w.startsWith("(") && w.drop(1).takeWhile { it != ')' }.all { !it.isLetter() || it.isUpperCase() }) w else titleCase(w)
    }

private val vtopDay = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH)
private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "11-Dec-2026 - 12-Dec-2026" → "Fri 11 Dec to Sat 12 Dec"; one day stays one. */
internal fun eventDays(s: String): String {
    val days = Regex("""\d{2}-[A-Za-z]{3}-\d{4}""").findAll(s).mapNotNull { runCatching { LocalDate.parse(it.value, vtopDay) }.getOrNull() }.toList()
    return when {
        days.isEmpty() -> s
        days.size == 1 || days[0] == days[1] -> days[0].format(shortDay)
        else -> days[0].format(shortDay) + " to " + days[1].format(shortDay)
    }
}

/** "2026-10-27 - 2026-12-11" → "Fri 11 Dec". */
private fun registrationEnds(s: String): String? =
    Regex("""\d{4}-\d{2}-\d{2}""").findAll(s).lastOrNull()?.let { runCatching { LocalDate.parse(it.value).format(shortDay) }.getOrNull() }
