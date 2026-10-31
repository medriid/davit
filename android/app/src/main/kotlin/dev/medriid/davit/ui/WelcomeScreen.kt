@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.medriid.davit.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.Repo
import dev.medriid.davit.data.UiState
import dev.medriid.davit.design.HalftoneBar
import dev.medriid.davit.design.Heading
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkCard
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.InkPairs
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.Seal

/**
 * Before signing in: make daVIT yours. Classic or Editorial, light or dark, the ink colours and your mascot,
 * each change shown at once on a sample card, then on to the sign-in form. All of it
 * stays in Settings → Look afterwards.
 */
@Composable
internal fun WelcomeScreen(state: UiState, repo: Repo, onContinue: () -> Unit) {
    // The Soft look opens on its coral intro first.
    var intro by rememberSaveable { mutableStateOf(true) }
    if (LocalInks.current.soft && intro) {
        SoftIntro { intro = false }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Seal(size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Heading("daVIT", 30.sp, uppercase = false)
        }
        Spacer(Modifier.height(26.dp))
        Heading("Welcome", 36.sp)
        Spacer(Modifier.height(6.dp))
        InkText("Your attendance, classes, marks and the campus map, from VTOP. First, pick how it looks.")
        Spacer(Modifier.height(18.dp))
        GroupLabel("Style", Modifier.padding(bottom = 8.dp))
        val editorial = LocalInks.current.editorial
        StylePicker(editorial, repo::setEditorial)
        Spacer(Modifier.height(14.dp))
        SampleCard(Mascot.parse(state.mascot))

        GroupLabel("Light or dark", Modifier.padding(top = 22.dp, bottom = 8.dp))
        ChoiceRow(listOf("System", "Light", "Dark"), state.theme) { repo.setTheme(it) }

        // Colours belong to Classic; Editorial keeps its own newsprint and red.
        if (!editorial) {
            GroupLabel("Colours", Modifier.padding(top = 20.dp, bottom = 8.dp))
            val palette = state.palette
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InkPairs.classic.forEach { i ->
                    InkSwatch(InkPairs.of(i, LocalInks.current.dark), selected = !palette.custom && i == palette.base) { repo.setInk(i) }
                }
            }
            Spacer(Modifier.height(6.dp))
            InkText(InkPairs.all[palette.base.coerceIn(0, InkPairs.all.lastIndex)].name + ". Any colour for every ink and card later, in Settings.", style = RisoType.small)
        }

        GroupLabel("Your mascot", Modifier.padding(top = 20.dp, bottom = 8.dp))
        MascotEditor(Mascot.parse(state.mascot)) { repo.setMascot(it.encode()) }

        Spacer(Modifier.height(26.dp))
        InkButton("Continue to sign in", onContinue, fillWidth = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        InkText("You can change all of this any time: tap your mascot, then Look.", style = RisoType.small)
    }
}

/** A class card as Today shows it, so each pick is seen on the real thing. */
@Composable
private fun SampleCard(mascot: Mascot) {
    InkCard(Modifier.fillMaxWidth(), kind = Kind.Classes, plateOffset = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                InkText("Now", label = true)
                InkText("Applied Science", style = RisoType.title)
                InkText("10:00 – 10:50 · SJT-401", style = RisoType.small)
            }
            MascotAvatar(mascot, 52.dp)
        }
        Spacer(Modifier.height(10.dp))
        HalftoneBar(0.82f, height = 12.dp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkText("82% · can skip 3", style = RisoType.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            InkChip("Live", solid = true)
        }
    }
}
