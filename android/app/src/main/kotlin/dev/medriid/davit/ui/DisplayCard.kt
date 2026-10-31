package dev.medriid.davit.ui

import androidx.compose.ui.graphics.compositeOver
import dev.medriid.davit.design.EditorialShape
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.medriid.davit.data.Banner
import dev.medriid.davit.data.BannerStat
import dev.medriid.davit.design.InkButton
import dev.medriid.davit.design.InkText
import dev.medriid.davit.design.Inks
import dev.medriid.davit.design.Kind
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun StatChip(text: String) {
    val inks = LocalInks.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(inks.paper)
            .border(1.5.dp, inks.text, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { BasicText(text, maxLines = 1, style = RisoType.small.copy(color = inks.text, fontWeight = FontWeight.SemiBold)) }
}

/** Your photo, cropped to the card at [Banner.focus], or the chosen built-in print. */
@Composable
internal fun BannerBackground(banner: Banner, photoAt: Long, photo: File, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, banner.photo, photoAt) {
        value = if (banner.photo && photo.exists()) withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(photo.path)?.asImageBitmap() }.getOrNull()
        } else null
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription = null, contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, banner.focus * 2 - 1), modifier = modifier)
    } else {
        BannerArt(banner.style, modifier)
    }
}

/** The built-in prints, drawn in your inks and card colours so they follow your theme. */
@Composable
internal fun BannerArt(style: Int, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    Canvas(modifier.background(inks.fill(Kind.Classes))) { drawArt(style, inks) }
}

private fun DrawScope.drawArt(style: Int, inks: Inks) {
    // Editorial cards all share one surface, so its prints step through surface, raised and a red wash instead.
    val ramp = listOf(inks.surface, inks.raised, inks.a.copy(alpha = 0.2f).compositeOver(inks.surface))
    val soft = if (inks.editorial) Kind.entries.indices.map { ramp[it % 3] }
    else Kind.entries.map { inks.fill(it).takeIf { f -> f != inks.paper } ?: inks.mark(it).copy(alpha = 0.35f) }
    val line = Stroke(2.dp.toPx())
    when (style) {
        // Waves: four bands of card colours, outlined like the cards.
        0 -> {
            drawRect(soft[1])
            listOf(0.3f, 0.5f, 0.7f, 0.88f).forEachIndexed { i, base ->
                val p = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, size.height * base)
                    var x = 0f
                    while (x <= size.width) {
                        lineTo(x, size.height * base + sin(x / size.width * 2 * PI * (1.3 + i * 0.4) + i).toFloat() * size.height * 0.06f)
                        x += 6f
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(p, listOf(soft[2], soft[0], soft[3], soft[5])[i])
                drawPath(p, inks.text, style = line)
            }
        }
        // Sun: a big ink-A disc with halftone rays on a sky wash.
        1 -> {
            drawRect(soft[6])
            val c = Offset(size.width * 0.72f, size.height * 0.62f)
            for (r in 1..6) drawCircle(inks.a.copy(alpha = 0.12f * (7 - r) / 6f), size.height * (0.32f + r * 0.12f), c)
            drawCircle(inks.a, size.height * 0.32f, c)
            drawCircle(inks.text, size.height * 0.32f, c, style = line)
            drawRect(soft[0], Offset(0f, size.height * 0.82f), Size(size.width, size.height * 0.18f))
            drawLine(inks.text, Offset(0f, size.height * 0.82f), Offset(size.width, size.height * 0.82f), 2.dp.toPx())
        }
        // Confetti: scattered dots, rings and squiggles.
        2 -> {
            drawRect(soft[4])
            val rnd = Random(11)
            repeat(26) {
                val o = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)
                val r = (6 + rnd.nextInt(14)).dp.toPx() / 2
                val c = listOf(inks.a, inks.b, soft[3], soft[5], soft[0])[rnd.nextInt(5)]
                when (rnd.nextInt(3)) {
                    0 -> drawCircle(c, r, o)
                    1 -> drawCircle(inks.text, r, o, style = line)
                    else -> drawLine(c, o, o + Offset(r * 2, -r), 3.dp.toPx())
                }
            }
        }
        // Blocks: a grid of coloured panels with ink lines.
        3 -> {
            val cols = listOf(0f, 0.28f, 0.55f, 0.8f, 1f)
            val rows = listOf(0f, 0.45f, 1f)
            var k = 0
            for (r in 0 until rows.size - 1) for (c in 0 until cols.size - 1) {
                val tl = Offset(size.width * cols[c], size.height * rows[r])
                val br = Offset(size.width * cols[c + 1], size.height * rows[r + 1])
                drawRect(if (k == 5) inks.a else soft[(k * 3) % soft.size], tl, Size(br.x - tl.x, br.y - tl.y))
                drawRect(inks.text, tl, Size(br.x - tl.x, br.y - tl.y), style = line)
                k++
            }
        }
        // Stripes: two card colours on the diagonal.
        4 -> {
            drawRect(soft[2])
            val w = 22.dp.toPx()
            var x = -size.height
            var i = 0
            while (x < size.width) {
                if (i % 2 == 0) {
                    val p = Path().apply {
                        moveTo(x, size.height); lineTo(x + w, size.height); lineTo(x + w + size.height, 0f); lineTo(x + size.height, 0f); close()
                    }
                    drawPath(p, soft[7])
                }
                x += w
                i++
            }
        }
        // Halftone: ink A dots growing across the card.
        else -> {
            drawRect(inks.paper)
            val step = 12.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(inks.a, step * 0.48f * (x / size.width), Offset(x, y))
                    x += step
                }
                y += step
            }
        }
    }
}

/** Make the top bar yours: a built-in print or your own photo behind it, the two stats it shows, and your name. */
@Composable
internal fun DisplayCardScreen(route: Route) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    val inks = LocalInks.current
    val b = state.banner
    var note by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { note = if (repo.setBannerPhoto(uri)) null else "Couldn't read that image." }
    }
    val editorial = inks.editorial
    val tile = if (editorial) EditorialShape else RoundedCornerShape(10.dp)
    RouteScreen(
        route,
        "Display card",
        if (editorial) "The picture at the top of Home: a print or your own photo. Nothing leaves this phone."
        else "The top bar on Home, printed on a background of your choice. Nothing leaves this phone.",
    ) {
        // As it looks on Home, changing as you pick.
        if (editorial) EditorialPicture(state.snapshot, onClick = {}, onChange = null)
        else TopBar(state, Mascot.parse(state.mascot), repo.bannerPhoto, card = true, onSync = {}, onProfile = {}, onMore = {}, gutter = 0.dp)

        GroupLabel("Background", Modifier.padding(top = 18.dp, bottom = 8.dp))
        Banner.Styles.chunked(3).forEachIndexed { row, names ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                names.forEachIndexed { i, label ->
                    val index = row * 3 + i
                    val on = !b.photo && b.style == index
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.RadioButton) { repo.setBanner(b.copy(style = index, photo = false)) }
                            .semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BannerArt(
                            index,
                            Modifier.fillMaxWidth().height(56.dp).clip(tile)
                                .border(
                                    if (on) (if (editorial) 2.dp else 3.dp) else (if (editorial) 1.dp else 1.5.dp),
                                    if (on || !editorial) inks.text else inks.rule,
                                    tile,
                                ),
                        )
                        InkText(label, style = RisoType.small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton(if (b.photo) "Another photo" else "Your own photo", {
                runCatching { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }, modifier = Modifier.weight(1f), fillWidth = true)
            if (b.photo) InkButton("Remove photo", repo::removeBannerPhoto, quiet = true, modifier = Modifier.weight(1f), fillWidth = true)
        }
        note?.let { InkText(it, style = RisoType.small, modifier = Modifier.padding(top = 6.dp)) }
        if (b.photo) {
            GroupLabel("Which part of the photo", Modifier.padding(top = 16.dp, bottom = 8.dp))
            val stops = listOf(0f, 0.5f, 1f)
            ChoiceRow(listOf("Top", "Middle", "Bottom"), stops.indexOfFirst { kotlin.math.abs(it - b.focus) < 0.2f }) {
                repo.setBanner(b.copy(focus = stops[it]))
            }
        }

        // Editorial's picture carries the attendance figure, not these.
        if (editorial) return@RouteScreen
        GroupLabel("First stat", Modifier.padding(top = 18.dp, bottom = 8.dp))
        ChoiceRow(BannerStat.entries.map { it.title }, BannerStat.of(b.left).ordinal) { repo.setBanner(b.copy(left = BannerStat.entries[it].id)) }
        GroupLabel("Second stat", Modifier.padding(top = 16.dp, bottom = 8.dp))
        ChoiceRow(BannerStat.entries.map { it.title }, BannerStat.of(b.right).ordinal) { repo.setBanner(b.copy(right = BannerStat.entries[it].id)) }

        GroupLabel("In the bar", Modifier.padding(top = 18.dp, bottom = 4.dp))
        ToggleRow("Your name instead of daVIT", "As VTOP has it. Only on your screen.", b.nameInBar) { repo.setBanner(b.copy(nameInBar = !b.nameInBar)) }
        ToggleRow("Print the bar on a card", "Off for a plain top bar.", !b.hidden) { repo.setBanner(b.copy(hidden = !b.hidden)) }
        Spacer(Modifier.height(10.dp))
        InkText("Your mascot, in the bar's corner, is changed in Settings → Look.", style = RisoType.small)
    }
}
