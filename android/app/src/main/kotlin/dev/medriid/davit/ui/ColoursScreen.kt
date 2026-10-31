@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.medriid.davit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Palette
import dev.medriid.davit.design.CardShape
import dev.medriid.davit.design.ColorWheel
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkField
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoInks
import dev.medriid.davit.design.RisoTheme
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.ValueSlider
import dev.medriid.davit.design.argb
import dev.medriid.davit.design.contrast
import dev.medriid.davit.design.hex
import dev.medriid.davit.design.hsvColor
import dev.medriid.davit.design.parseHex
import dev.medriid.davit.design.rememberHaptics
import dev.medriid.davit.design.toHsv
import java.util.Locale

/** One colour you can change. */
private sealed class InkRole(val title: String, val note: String) {
    data object Primary : InkRole("Primary", "Plates, big numbers, the seal")
    data object Secondary : InkRole("Secondary", "Headings, chips, buttons, lines")
    data class Paper(val night: Boolean) : InkRole(if (night) "Night paper" else "Paper", if (night) "The page at night" else "The page by day")
    data class Text(val night: Boolean) : InkRole(if (night) "Text at night" else "Text", "Body text and borders")
    data class Card(val kind: Kind) : InkRole(kind.title, kind.note)
}

/** The colour [role] has in [p], and whether it's your own or worked out / the pair's. */
private fun Palette.colourOf(role: InkRole, dayInks: Inks, nightInks: Inks): Pair<Color, Boolean> = when (role) {
    InkRole.Primary -> dayInks.a to (a != null)
    InkRole.Secondary -> dayInks.b to (b != null)
    is InkRole.Paper -> (if (role.night) nightInks.paper else dayInks.paper) to ((if (role.night) nightPaper else paper) != null)
    is InkRole.Text -> (if (role.night) nightInks.text else dayInks.text) to ((if (role.night) nightText else text) != null)
    is InkRole.Card -> kind(role.kind) to (role.kind.name in kinds)
}

/** [p] with [role] set to [c], or back to the pair's / worked out for null. */
private fun Palette.with(role: InkRole, c: Color?): Palette {
    val v = c?.argb()
    return when (role) {
        InkRole.Primary -> copy(a = v)
        InkRole.Secondary -> copy(b = v)
        is InkRole.Paper -> if (role.night) copy(nightPaper = v) else copy(paper = v)
        is InkRole.Text -> if (role.night) copy(nightText = v) else copy(text = v)
        is InkRole.Card -> withKind(role.kind, c)
    }
}

/**
 * Your colours, every one of them: primary and secondary inks, paper and text by day and
 * at night, and a colour for each kind of card. Tap one for the wheel; the whole app
 * changes as you drag, and Cancel puts it back.
 */
@Composable
internal fun ColoursScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val palette = state.palette
    var night by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<InkRole?>(null) }
    var startingOver by remember { mutableStateOf<Int?>(null) }
    val day = palette.inks(false)
    val dark = palette.inks(true)

    Box(Modifier.fillMaxSize()) {
        RouteScreen(route, "Colours", "Any colour for every ink and kind of card. The app changes as you pick.") {
            // What it looks like, by day or at night.
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkText("Preview", label = true, modifier = Modifier.weight(1f))
                ChoiceRow(listOf("Day", "Night"), if (night) 1 else 0) { night = it == 1 }
            }
            Spacer(Modifier.height(8.dp))
            Preview(if (night) dark else day)

            GroupLabel("Inks")
            InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
                listOf(InkRole.Primary, InkRole.Secondary, InkRole.Paper(night), InkRole.Text(night)).forEachIndexed { i, role ->
                    if (i > 0) Hairline()
                    val (c, own) = palette.colourOf(role, day, dark)
                    ColourRow(role, c, own, null) { editing = role }
                }
                if (night && palette.b != null && dark.b != day.b) {
                    Spacer(Modifier.height(6.dp))
                    InkText("Your secondary is lifted a little at night so it reads on the dark paper.", style = RisoType.small)
                }
            }

            GroupLabel("Cards")
            InkCard(Modifier.fillMaxWidth(), plateOffset = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Switch) { repo.setPalette(palette.copy(tinted = !palette.tinted)) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        InkText("Tinted cards", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                        InkText("Each card's paper takes a wash of its colour, not just the plate behind it.", style = RisoType.small)
                    }
                    Spacer(Modifier.width(10.dp))
                    InkChip(if (palette.tinted) "On" else "Off", solid = palette.tinted)
                }
                Kind.entries.forEach { k ->
                    Hairline()
                    val role = InkRole.Card(k)
                    val (c, own) = palette.colourOf(role, day, dark)
                    ColourRow(role, c, own, k) { editing = role }
                }
            }

            GroupLabel("Start from")
            InkText("A pair to build on. Starting over drops the colours you changed.", style = RisoType.small)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InkPairs.classic.forEach { i ->
                    PairChip(InkPairs.of(i, night), selected = i == palette.base) { if (palette.custom) startingOver = i else repo.setInk(i) }
                }
            }
            startingOver?.let { i ->
                Spacer(Modifier.height(10.dp))
                InkText("Start over from ${InkPairs.all[i].name}?", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.clickable(role = Role.Button) { repo.setInk(i); startingOver = null }) { InkChip("Start over", solid = true) }
                    Box(Modifier.clickable(role = Role.Button) { startingOver = null }) { InkChip("Keep mine") }
                }
            }
        }

        val role = editing
        AnimatedVisibility(role != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(MutableInteractionSource(), null) { })
        }
        AnimatedVisibility(
            role != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            // Keep drawing the last role while the sheet slides away.
            var shown by remember { mutableStateOf<InkRole>(InkRole.Primary) }
            if (role != null) shown = role
            PickerSheet(shown, palette, day, dark, onClose = { editing = null })
        }
    }
}

/** Swatch, name, what it colours, and its hex (or "Auto"). */
@Composable
private fun ColourRow(role: InkRole, c: Color, own: Boolean, kind: Kind?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Change ${role.title}", onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(c, kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            InkText(role.title, style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
            InkText(role.note, style = RisoType.small, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            InkText(c.hex(), style = RisoType.small.copy(fontWeight = FontWeight.SemiBold))
            if (!own) InkText(if (role is InkRole.Text) "auto" else "default", style = RisoType.small.copy(color = LocalInks.current.rule))
        }
    }
}

/** A colour as a little card: the plate behind, and for a card kind, its washed paper on top. */
@Composable
private fun Swatch(c: Color, kind: Kind?, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val inks = LocalInks.current
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(size + 4.dp)) {
        Box(Modifier.size(size).offset(4.dp, 4.dp).clip(shape).background(c))
        Box(
            Modifier.size(size).clip(shape)
                .background(if (kind != null) androidx.compose.ui.graphics.lerp(inks.paper, c, if (inks.tinted) 0.18f else 0f) else c)
                .border(2.dp, inks.text, shape),
        )
    }
}

/** An ink pair to start from: its two inks overlapping, and its name under the selected one. */
@Composable
private fun PairChip(pair: Inks, selected: Boolean, onClick: () -> Unit) {
    val current = LocalInks.current
    Box(
        Modifier
            .size(52.dp)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) current.text else current.rule, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = pair.name
                this.selected = selected
            }
            .padding(10.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.34f
            drawCircle(pair.a, r, androidx.compose.ui.geometry.Offset(r, r), blendMode = current.blend)
            drawCircle(pair.b, r, androidx.compose.ui.geometry.Offset(size.width - r, size.height - r), blendMode = current.blend)
        }
    }
}

/** A small slice of the app in [inks]: a heading, a class and a due card, a chip and a button. */
@Composable
private fun Preview(inks: Inks) {
    RisoTheme(inks) {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(inks.paper).border(2.dp, inks.text, CardShape).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Today", 22.sp, Modifier.weight(1f))
                InkChip("Live", solid = true)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkCard(Modifier.weight(1f).fillMaxHeight(), kind = Kind.Classes, plateOffset = 3.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    InkText("Class", label = true)
                    InkText("Calculus", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText("09:00 · MB-210", style = RisoType.small)
                }
                InkCard(Modifier.weight(1f).fillMaxHeight(), kind = Kind.Assignments, plateOffset = 3.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    InkText("Due", label = true)
                    InkText("Lab report", style = RisoType.body.copy(fontWeight = FontWeight.SemiBold))
                    InkText("in 2 days", style = RisoType.small)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkCard(Modifier.weight(1f).fillMaxHeight(), kind = Kind.Exams, plateOffset = 3.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    InkText("Exam", label = true)
                    InkText("CAT 2 · Sat", style = RisoType.small)
                }
                InkCard(Modifier.weight(1f).fillMaxHeight(), kind = Kind.Events, plateOffset = 3.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                    InkText("Event", label = true)
                    InkText("Quiz night", style = RisoType.small)
                }
            }
            Spacer(Modifier.height(8.dp))
            InkButton("Button", {}, fillWidth = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The wheel for one colour, over the screen: hue and saturation on the wheel, brightness
 * below, the hex to type, Riso's own inks to start from, and how well it reads. Every move
 * shows across the app at once; Done keeps it, Cancel (or back) puts the old one back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerSheet(role: InkRole, palette: Palette, day: Inks, dark: Inks, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val inks = LocalInks.current
    val haptics = rememberHaptics()
    // What was there when the sheet opened, for Cancel.
    val before = remember(role) { palette }
    val (start, own) = remember(role) { palette.colourOf(role, day, dark) }
    val hsv = remember(role) { start.toHsv() }
    var h by remember(role) { mutableFloatStateOf(hsv[0]) }
    var s by remember(role) { mutableFloatStateOf(hsv[1]) }
    var v by remember(role) { mutableFloatStateOf(hsv[2]) }
    var hexText by remember(role) { mutableStateOf(start.hex()) }
    var auto by remember(role) { mutableStateOf(!own) }
    val colour = hsvColor(h, s, v)

    /** Shows [c] for this colour across the app while choosing; nothing is kept until Done. */
    fun show(c: Color?) = repo.previewPalette(before.with(role, c))
    fun set(c: Color) {
        val x = c.toHsv()
        // Keep the hue where it was when the colour is grey or black, so the wheel doesn't jump.
        if (x[1] > 0.001f && x[2] > 0.001f) h = x[0]
        if (x[2] > 0.001f) s = x[1]
        v = x[2]
        hexText = c.hex()
        auto = false
        show(c)
    }
    BackHandler { repo.setPalette(before); onClose() }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(inks.paper)
            .border(2.dp, inks.text, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .clickable(MutableInteractionSource(), null) { },
    ) {
        // Everything scrolls but the two buttons, which stay at the bottom of the sheet.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(top = 14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(44.dp, 4.dp).clip(CircleShape).background(inks.rule))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Heading(role.title, 22.sp)
                    InkText(role.note, style = RisoType.small, maxLines = 2)
                }
                Spacer(Modifier.width(10.dp))
                // Before and after, side by side.
                Swatch(start, (role as? InkRole.Card)?.kind, 30.dp)
                InkText("→", style = RisoType.body, modifier = Modifier.padding(horizontal = 6.dp))
                Swatch(if (auto) palette.colourOf(role, palette.inks(false), palette.inks(true)).first else colour, (role as? InkRole.Card)?.kind, 30.dp)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ColorWheel(
                    h, s, v,
                    onChange = { hh, ss ->
                        h = hh
                        s = ss
                        if (v < 0.15f) v = 0.6f
                        auto = false
                        hexText = hsvColor(h, s, v).hex()
                        show(hsvColor(h, s, v))
                    },
                    modifier = Modifier.widthIn(max = 240.dp),
                    onEnd = { haptics.tick() },
                )
            }
            Spacer(Modifier.height(8.dp))
            InkText("Brightness", label = true)
            ValueSlider(h, s, v, onChange = {
                v = it
                auto = false
                hexText = hsvColor(h, s, v).hex()
                show(hsvColor(h, s, v))
            }, onEnd = { haptics.tick() })
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                InkField(hexText, { t ->
                    hexText = t.take(7)
                    parseHex(t)?.let { set(it) }
                }, "Hex", Modifier.weight(1f))
                if (role is InkRole.Text || own) {
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.padding(bottom = 14.dp).clickable(role = Role.Button) {
                            auto = true
                            show(null)
                            val c = palette.with(role, null).colourOf(role, before.with(role, null).inks(false), before.with(role, null).inks(true)).first
                            val x = c.toHsv()
                            h = x[0]; s = x[1]; v = x[2]
                            hexText = c.hex()
                        },
                    ) { InkChip(if (role is InkRole.Text) "Auto" else "Default", solid = auto) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Readability(role, if (auto) null else colour, palette, day, dark)

            Spacer(Modifier.height(14.dp))
            // The ink you are on, by name, when it is one of Riso's.
            val named = if (auto) null else RisoInks.firstOrNull { it.color.hex() == colour.hex() }?.name
            InkText("Riso inks" + (named?.let { " · $it" } ?: ""), label = true)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RisoInks.forEach { ink ->
                    val on = !auto && ink.color.hex() == colour.hex()
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            // The picked one gets a paper ring inside its outline, like the wheel's thumb.
                            .background(if (on) inks.paper else ink.color)
                            .border(if (on) 2.5.dp else 1.5.dp, inks.text, CircleShape)
                            .padding(if (on) 5.dp else 0.dp)
                            .clip(CircleShape)
                            .background(ink.color)
                            .clickable(role = Role.RadioButton) {
                                haptics.tick()
                                set(ink.color)
                            }
                            .semantics { contentDescription = ink.name; selected = on },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.padding(horizontal = Gutter).padding(top = 10.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Cancel", { repo.setPalette(before); onClose() }, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
            InkButton("Done", {
                haptics.confirm()
                repo.setPalette(if (auto) before.with(role, null) else before.with(role, colour))
                onClose()
            }, modifier = Modifier.weight(1f), fillWidth = true)
        }
    }
}

/** How well the colour reads where it's used, in a plain sentence with the contrast ratio. */
@Composable
private fun Readability(role: InkRole, picked: Color?, palette: Palette, day: Inks, dark: Inks) {
    val inks = LocalInks.current
    val p = if (picked == null) palette else palette.with(role, picked)
    val d = p.inks(false)
    val n = p.inks(true)
    fun ratio(x: Float) = String.format(Locale.ENGLISH, "%.1f:1", x)
    val (ok, line) = when (role) {
        InkRole.Primary -> {
            val r = d.a.contrast(d.paper)
            (r >= 1.6f) to (if (r >= 1.6f) "Stands out on the paper · ${ratio(r)}" else "Faint on the paper · ${ratio(r)}: big numbers and plates may wash out")
        }
        InkRole.Secondary -> {
            val r = d.b.contrast(d.paper)
            (r >= 3f) to (if (r >= 3f) "Headings and chips read well · ${ratio(r)}" else "Headings and chip text may be hard to read · ${ratio(r)}; 3:1 or more reads well")
        }
        is InkRole.Paper -> {
            val i = if (role.night) n else d
            val r = i.text.contrast(i.paper)
            (r >= 4.5f) to "Text on it: ${i.text.hex()} · ${ratio(r)}" + if (r >= 4.5f) "" else ", hard to read"
        }
        is InkRole.Text -> {
            val i = if (role.night) n else d
            val r = i.text.contrast(i.paper)
            (r >= 4.5f) to (if (r >= 4.5f) "Reads well · ${ratio(r)}" else "Hard to read on the paper · ${ratio(r)}; 4.5:1 or more reads well")
        }
        is InkRole.Card -> {
            val r = d.plate(role.kind).contrast(d.paper)
            (r >= 1.3f) to (if (r >= 1.3f) "Shows clearly on the paper · ${ratio(r)}" else "Barely shows on the paper · ${ratio(r)}")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) inks.b else inks.a))
        Spacer(Modifier.width(8.dp))
        InkText(line, style = RisoType.small.copy(fontWeight = if (ok) FontWeight.Normal else FontWeight.SemiBold))
    }
}
