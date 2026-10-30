package dev.medriid.davit.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.medriid.davit.data.ClassSlot
import dev.medriid.davit.data.atRisk
import dev.medriid.davit.data.clock
import dev.medriid.davit.data.mustAttend
import dev.medriid.davit.design.InkChip
import dev.medriid.davit.design.LocalInks
import dev.medriid.davit.design.RisoType
import dev.medriid.davit.design.halftone
import dev.medriid.davit.design.rememberMotionEnabled
import dev.medriid.davit.design.trimToCaps
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.sin

/** A point on the path: a class, or a long gap between two. Times are minutes since midnight. */
private sealed interface Stop {
    /** When you reach this point and when you leave it. */
    val enter: Int
    val exit: Int
}

private data class ClassStop(val index: Int, val slot: ClassSlot) : Stop {
    override val enter get() = slot.meeting.start
    override val exit get() = slot.meeting.end
}

private data class FreeStop(val from: Int, val to: Int) : Stop {
    override val enter get() = (from + to) / 2
    override val exit get() = enter
}

/** A gap this long gets its own stretch of path, so lunch doesn't read as back-to-back classes. */
private const val FreeGap = 60

private val StopWidth = 112.dp
private val FreeWidth = 72.dp
private val Node = 62.dp
private val ChipRoom = 30.dp
private val Wave = 26.dp
private val NodeArea = ChipRoom + Wave + Node + 12.dp
private val LabelArea = 60.dp

private fun stopsOf(slots: List<ClassSlot>): List<Stop> = buildList {
    slots.forEachIndexed { i, s ->
        val prev = slots.getOrNull(i - 1)
        if (prev != null && s.meeting.start - prev.meeting.end >= FreeGap) add(FreeStop(prev.meeting.end, s.meeting.start))
        add(ClassStop(i, s))
    }
}

/** How far down the wave stop [j] sits, 0..1. */
private fun wave(j: Int) = 0.5f + 0.5f * sin(j * 1.7f)

/**
 * Today's classes as a path of stops you swipe along: earlier ones to the left, later
 * ones to the right. Done stops are pressed flat into a halftone, the live one is solid
 * ink A on an ink-B plate, the rest stand on ink-A plates like buttons, and an at-risk
 * course carries a "!" tab. The line is solid ink B as far as the day has got.
 *
 * Whichever class settles in the middle is [selected]; tapping a stop brings it there.
 */
@Composable
internal fun DayPath(
    slots: List<ClassSlot>,
    minute: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inks = LocalInks.current
    val stops = remember(slots) { stopsOf(slots) }
    fun itemOf(index: Int) = stops.indexOfFirst { it is ClassStop && it.index == index }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = itemOf(selected))
    val select by rememberUpdatedState(onSelect)

    LaunchedEffect(selected, stops) { state.animateScrollToItem(itemOf(selected)) }
    // When a swipe settles, the class in the middle becomes the one shown below.
    LaunchedEffect(state, stops) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            val info = state.layoutInfo
            val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val mid = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) } ?: return@collect
            (stops.getOrNull(mid.index) as? ClassStop)?.let { select(it.index) }
        }
    }

    BoxWithConstraints(modifier.bleed()) {
        val side = ((maxWidth - StopWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            modifier = Modifier.fillMaxWidth().height(NodeArea + LabelArea),
        ) {
            itemsIndexed(stops) { j, stop ->
                val prev = stops.getOrNull(j - 1)
                val next = stops.getOrNull(j + 1)
                val nodeY = ChipRoom + Node / 2 + Wave * wave(j)
                Box(
                    Modifier
                        .width(if (stop is ClassStop) StopWidth else FreeWidth)
                        .height(NodeArea + LabelArea)
                        .drawBehind {
                            val y = { k: Int -> (ChipRoom + Node / 2 + Wave * wave(k)).toPx() }
                            val mid = size.width / 2
                            if (prev != null) {
                                line(0f, (y(j - 1) + y(j)) / 2, mid, y(j), (prev.exit + stop.enter) / 2, stop.enter, minute, inks.rule, inks.b, inks.blend)
                            }
                            if (next != null) {
                                line(mid, y(j), size.width, (y(j) + y(j + 1)) / 2, stop.exit, (stop.exit + next.enter) / 2, minute, inks.rule, inks.b, inks.blend)
                            }
                        },
                ) {
                    when (stop) {
                        is ClassStop -> ClassStopItem(stop.slot, minute, nodeY, stop.index == selected) { onSelect(stop.index) }
                        is FreeStop -> FreeStopItem(stop)
                    }
                }
            }
        }
    }
}

/** Half a stretch of line from (x0, y0) to (x1, y1), covering [t0]..[t1]: dashed ahead, solid where the day has got to. */
private fun DrawScope.line(
    x0: Float, y0: Float, x1: Float, y1: Float,
    t0: Int, t1: Int, minute: Int,
    ahead: Color, travelled: Color, blend: BlendMode,
) {
    val path = Path().apply {
        moveTo(x0, y0)
        val cx = (x0 + x1) / 2
        cubicTo(cx, y0, cx, y1, x1, y1)
    }
    val done = when {
        minute >= t1 -> 1f
        minute <= t0 -> 0f
        else -> (minute - t0).toFloat() / (t1 - t0)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val split = measure.length * done
    // Each part only where it belongs: the dashes would print through the solid line under multiply.
    if (done < 1f) {
        val rest = Path()
        measure.getSegment(split, measure.length, rest, true)
        drawPath(rest, ahead, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()))))
    }
    if (done > 0f) {
        val part = Path()
        measure.getSegment(0f, split, part, true)
        drawPath(part, travelled, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round), blendMode = blend)
    }
}

@Composable
private fun ClassStopItem(slot: ClassSlot, minute: Int, nodeY: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    val inks = LocalInks.current
    val m = slot.meeting
    val done = m.end <= minute
    val live = m.start <= minute && minute < m.end
    val risk = !done && slot.attendance?.atRisk == true
    val second = when {
        done -> "done"
        risk -> "attend ${slot.attendance!!.mustAttend}"
        else -> slot.course.venue.ifBlank { if (slot.isLab) "lab" else "theory" }
    }
    val said = listOf(slot.course.courseName, "${clock(m.start)} to ${clock(m.end)}", if (live) "on now" else second).joinToString(", ")

    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = said }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        if (live) {
            InkChip("NOW", Modifier.align(Alignment.TopCenter).offset(y = nodeY - Node / 2 - 28.dp), solid = true)
        }
        StopNode(clock(m.start), done, live, risk, Modifier.align(Alignment.TopCenter).offset(y = nodeY - Node / 2))
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = NodeArea).width(StopWidth).padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                slot.course.courseName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = RisoType.small.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = inks.text, textAlign = TextAlign.Center),
            )
            BasicText(
                second.uppercase(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = RisoType.label.copy(color = if (risk) inks.a else inks.text, letterSpacing = RisoType.label.letterSpacing * 0.5f, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(5.dp))
            Box(Modifier.width(26.dp).height(3.dp).background(if (selected) inks.b else Color.Transparent))
        }
    }
}

/** One stop: a printed button. Pressed flat and dotted when done, solid ink A when live. */
@Composable
private fun StopNode(time: String, done: Boolean, live: Boolean, risk: Boolean, modifier: Modifier) {
    val inks = LocalInks.current
    // The time sits on ink A when live: paper or text ink, whichever reads better on it.
    val onA = if (contrast(inks.paper, inks.a) >= contrast(inks.text, inks.a)) inks.paper else inks.text
    val motion = rememberMotionEnabled()
    Box(modifier.size(Node + 5.dp)) {
        if (live && motion) {
            val pulse = rememberInfiniteTransition(label = "live")
            val grow by pulse.animateFloat(1f, 1.32f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "grow")
            Box(Modifier.size(Node).scale(grow).border(2.dp, inks.a.copy(alpha = (1.32f - grow) * 2.4f), CircleShape))
        }
        if (!done) {
            Box(Modifier.offset(4.dp, 5.dp).size(Node).clip(CircleShape).background(if (live) inks.b else inks.a))
        }
        Box(
            Modifier
                .size(Node)
                .clip(CircleShape)
                .background(if (live) inks.a else inks.paper)
                .then(if (done) Modifier.halftone(inks.b.copy(alpha = 0.45f), 6.dp) else Modifier)
                .border(2.dp, if (done) inks.rule else inks.text, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                time,
                style = RisoType.display(16.sp).copy(color = if (live) onA else inks.text),
                modifier = Modifier.trimToCaps(16.sp),
            )
        }
        if (risk) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(2.dp, (-4).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(inks.paper)
                    .border(2.dp, inks.text, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("!", style = RisoType.display(13.sp).copy(color = inks.a), modifier = Modifier.trimToCaps(13.sp))
            }
        }
    }
}

/** A long gap: just the line and how long you're free. */
@Composable
private fun FreeStopItem(stop: FreeStop) {
    val inks = LocalInks.current
    val mins = stop.to - stop.from
    val span = if (mins % 60 == 0) "${mins / 60} h" else "${mins / 60} h ${mins % 60}"
    Column(
        Modifier.fillMaxWidth().padding(top = NodeArea),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("FREE", style = RisoType.label.copy(color = inks.text, textAlign = TextAlign.Center))
        BasicText(span.uppercase(), style = RisoType.label.copy(color = inks.text, letterSpacing = RisoType.label.letterSpacing * 0.5f, textAlign = TextAlign.Center))
    }
}

private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
}

/** Runs edge to edge through the tab's side gutters, so the path scrolls in from the screen edge. */
private fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
    val extra = Gutter.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}
