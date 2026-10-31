package dev.medriid.davit.design

import androidx.compose.ui.unit.em
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Line icons drawn in a 24-unit box, 2-unit stroke, so they match the rule weight. */
enum class RisoIcon(val draw: DrawScope.(Color) -> Unit) {
    Today({ c ->
        val u = size.width / 24f
        val s = stroke(u)
        drawRoundRect(c, Offset(3 * u, 5 * u), Size(18 * u, 16 * u), CornerRadius(2 * u), style = s)
        drawLine(c, Offset(3 * u, 10 * u), Offset(21 * u, 10 * u), 2 * u)
        drawLine(c, Offset(8 * u, 3 * u), Offset(8 * u, 7 * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(16 * u, 3 * u), Offset(16 * u, 7 * u), 2 * u, StrokeCap.Round)
        drawCircle(c, 2.4f * u, Offset(12 * u, 15.5f * u))
    }),
    Attendance({ c ->
        val u = size.width / 24f
        drawRoundRect(c, Offset(3 * u, 3 * u), Size(18 * u, 18 * u), CornerRadius(3 * u), style = stroke(u))
        val tick = Path().apply {
            moveTo(7.5f * u, 12.5f * u); lineTo(10.5f * u, 15.5f * u); lineTo(16.5f * u, 8.5f * u)
        }
        drawPath(tick, c, style = stroke(u, 2.4f))
    }),
    Academics({ c ->
        val u = size.width / 24f
        val cap = Path().apply {
            moveTo(12 * u, 4 * u); lineTo(22 * u, 9 * u); lineTo(12 * u, 14 * u); lineTo(2 * u, 9 * u); close()
        }
        drawPath(cap, c, style = stroke(u))
        val band = Path().apply {
            moveTo(6 * u, 11.5f * u); lineTo(6 * u, 16.5f * u)
            quadraticTo(12 * u, 21 * u, 18 * u, 16.5f * u); lineTo(18 * u, 11.5f * u)
        }
        drawPath(band, c, style = stroke(u))
    }),
    Week({ c ->
        val u = size.width / 24f
        for (row in 0 until 2) for (col in 0 until 3) {
            drawRoundRect(
                c, Offset((3 + col * 6.5f) * u, (5 + row * 8) * u), Size(5 * u, 6 * u), CornerRadius(1.2f * u),
                style = stroke(u, 1.8f),
            )
        }
    }),
    Profile({ c ->
        val u = size.width / 24f
        drawCircle(c, 4.2f * u, Offset(12 * u, 8.5f * u), style = stroke(u))
        val body = Path().apply {
            moveTo(4.5f * u, 20.5f * u); quadraticTo(12 * u, 11 * u, 19.5f * u, 20.5f * u)
        }
        drawPath(body, c, style = stroke(u))
    }),

    /** A folded campus map. */
    Map({ c ->
        val u = size.width / 24f
        val sheet = Path().apply {
            moveTo(3 * u, 6 * u); lineTo(9 * u, 4 * u); lineTo(15 * u, 6 * u); lineTo(21 * u, 4 * u)
            lineTo(21 * u, 18 * u); lineTo(15 * u, 20 * u); lineTo(9 * u, 18 * u); lineTo(3 * u, 20 * u); close()
        }
        drawPath(sheet, c, style = stroke(u))
        drawLine(c, Offset(9 * u, 4 * u), Offset(9 * u, 18 * u), 1.8f * u)
        drawLine(c, Offset(15 * u, 6 * u), Offset(15 * u, 20 * u), 1.8f * u)
    }),

    /** Marks: rising bars. */
    Marks({ c ->
        val u = size.width / 24f
        val s = stroke(u, 1.9f)
        drawRoundRect(c, Offset(4 * u, 13 * u), Size(4 * u, 7 * u), CornerRadius(1 * u), style = s)
        drawRoundRect(c, Offset(10 * u, 9 * u), Size(4 * u, 11 * u), CornerRadius(1 * u), style = s)
        drawRoundRect(c, Offset(16 * u, 4 * u), Size(4 * u, 16 * u), CornerRadius(1 * u), style = s)
    }),

    /** Assignments: a page with a folded corner. */
    Assignments({ c ->
        val u = size.width / 24f
        val page = Path().apply {
            moveTo(6 * u, 3 * u); lineTo(14 * u, 3 * u); lineTo(19 * u, 8 * u); lineTo(19 * u, 21 * u); lineTo(6 * u, 21 * u); close()
        }
        drawPath(page, c, style = stroke(u))
        val fold = Path().apply { moveTo(14 * u, 3 * u); lineTo(14 * u, 8 * u); lineTo(19 * u, 8 * u) }
        drawPath(fold, c, style = stroke(u, 1.8f))
        drawLine(c, Offset(9 * u, 13 * u), Offset(16 * u, 13 * u), 1.8f * u, StrokeCap.Round)
        drawLine(c, Offset(9 * u, 17 * u), Offset(14 * u, 17 * u), 1.8f * u, StrokeCap.Round)
    }),

    /** Exams: a stopwatch. */
    Exams({ c ->
        val u = size.width / 24f
        drawCircle(c, 7.5f * u, Offset(12 * u, 13.5f * u), style = stroke(u))
        drawLine(c, Offset(12 * u, 13.5f * u), Offset(12 * u, 9.5f * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(12 * u, 13.5f * u), Offset(14.8f * u, 15.2f * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(9.5f * u, 2.8f * u), Offset(14.5f * u, 2.8f * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(12 * u, 2.8f * u), Offset(12 * u, 6 * u), 2 * u)
    }),

    /** Grades: a star. */
    Grades({ c ->
        val u = size.width / 24f
        val star = Path()
        for (i in 0 until 10) {
            val r = (if (i % 2 == 0) 9.2f else 4f) * u
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = 12 * u + r * kotlin.math.cos(a).toFloat()
            val y = 12.8f * u + r * kotlin.math.sin(a).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, c, style = stroke(u))
    }),

    /** Materials: a folder. */
    Materials({ c ->
        val u = size.width / 24f
        val folder = Path().apply {
            moveTo(3 * u, 18.5f * u); lineTo(3 * u, 5.5f * u); lineTo(9.5f * u, 5.5f * u); lineTo(11.5f * u, 8 * u)
            lineTo(21 * u, 8 * u); lineTo(21 * u, 18.5f * u); close()
        }
        drawPath(folder, c, style = stroke(u))
        drawLine(c, Offset(3 * u, 11.5f * u), Offset(21 * u, 11.5f * u), 1.8f * u)
    }),

    /** Faculty: someone at a board. */
    Faculty({ c ->
        val u = size.width / 24f
        drawRoundRect(c, Offset(10 * u, 3 * u), Size(11 * u, 9 * u), CornerRadius(1.2f * u), style = stroke(u, 1.8f))
        drawCircle(c, 2.6f * u, Offset(6.5f * u, 10.5f * u), style = stroke(u))
        val body = Path().apply { moveTo(2 * u, 21 * u); quadraticTo(6.5f * u, 13.5f * u, 11 * u, 21 * u) }
        drawPath(body, c, style = stroke(u))
        drawLine(c, Offset(9.5f * u, 15 * u), Offset(13 * u, 12 * u), 1.8f * u, StrokeCap.Round)
    }),

    /** Notifications: a bell. */
    Bell({ c ->
        val u = size.width / 24f
        val bell = Path().apply {
            moveTo(5 * u, 17 * u); lineTo(6.5f * u, 15 * u); lineTo(6.5f * u, 10.5f * u)
            cubicTo(6.5f * u, 4.5f * u, 17.5f * u, 4.5f * u, 17.5f * u, 10.5f * u)
            lineTo(17.5f * u, 15 * u); lineTo(19 * u, 17 * u); close()
        }
        drawPath(bell, c, style = stroke(u))
        drawLine(c, Offset(10 * u, 20.5f * u), Offset(14 * u, 20.5f * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(12 * u, 3 * u), Offset(12 * u, 4.6f * u), 2 * u, StrokeCap.Round)
    }),

    /** More from VTOP: four tiles. */
    More({ c ->
        val u = size.width / 24f
        for (x in listOf(3.5f, 13.5f)) for (y in listOf(3.5f, 13.5f)) {
            drawRoundRect(c, Offset(x * u, y * u), Size(7 * u, 7 * u), CornerRadius(1.6f * u), style = stroke(u))
        }
    }),

    /** Settings: a gear, eight teeth round a hub. */
    Settings({ c ->
        val u = size.width / 24f
        val mid = Offset(12 * u, 12 * u)
        drawCircle(c, 6 * u, mid, style = stroke(u))
        drawCircle(c, 2.4f * u, mid, style = stroke(u))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            drawLine(c, Offset(mid.x + dx * 6.6f * u, mid.y + dy * 6.6f * u), Offset(mid.x + dx * 9 * u, mid.y + dy * 9 * u), 2.6f * u, StrokeCap.Round)
        }
    }),

    /** Curriculum: a bound book. */
    Book({ c ->
        val u = size.width / 24f
        drawRoundRect(c, Offset(5 * u, 3 * u), Size(14 * u, 18 * u), CornerRadius(1.6f * u), style = stroke(u))
        drawLine(c, Offset(9 * u, 3 * u), Offset(9 * u, 21 * u), 2 * u)
        drawLine(c, Offset(12 * u, 8 * u), Offset(16 * u, 8 * u), 2 * u, StrokeCap.Round)
    }),

    /** Next semester: an arrow running up to a line. */
    Next({ c ->
        val u = size.width / 24f
        drawLine(c, Offset(4 * u, 12 * u), Offset(16 * u, 12 * u), 2 * u, StrokeCap.Round)
        drawPath(Path().apply { moveTo(12 * u, 7.5f * u); lineTo(16.5f * u, 12 * u); lineTo(12 * u, 16.5f * u) }, c, style = stroke(u))
        drawLine(c, Offset(20 * u, 5 * u), Offset(20 * u, 19 * u), 2 * u, StrokeCap.Round)
    }),

    /** Hostel: a house with a door. */
    Home({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(3 * u, 11 * u); lineTo(12 * u, 3.5f * u); lineTo(21 * u, 11 * u) }, c, style = stroke(u))
        drawPath(Path().apply { moveTo(6 * u, 9 * u); lineTo(6 * u, 20 * u); lineTo(18 * u, 20 * u); lineTo(18 * u, 9 * u) }, c, style = stroke(u))
        drawPath(Path().apply { moveTo(10 * u, 20 * u); lineTo(10 * u, 14 * u); lineTo(14 * u, 14 * u); lineTo(14 * u, 20 * u) }, c, style = stroke(u))
    }),

    /** Club events: a pennant. */
    Flag({ c ->
        val u = size.width / 24f
        drawLine(c, Offset(6 * u, 3 * u), Offset(6 * u, 21 * u), 2 * u, StrokeCap.Round)
        drawPath(Path().apply { moveTo(6 * u, 4 * u); lineTo(19 * u, 4 * u); lineTo(15.5f * u, 8.5f * u); lineTo(19 * u, 13 * u); lineTo(6 * u, 13 * u) }, c, style = stroke(u))
    }),

    /** Messages: a speech bubble. */
    Bubble({ c ->
        val u = size.width / 24f
        drawPath(
            Path().apply {
                moveTo(5 * u, 4 * u); lineTo(19 * u, 4 * u); quadraticTo(21 * u, 4 * u, 21 * u, 6 * u); lineTo(21 * u, 14 * u)
                quadraticTo(21 * u, 16 * u, 19 * u, 16 * u); lineTo(11 * u, 16 * u); lineTo(7 * u, 20 * u); lineTo(7 * u, 16 * u)
                lineTo(5 * u, 16 * u); quadraticTo(3 * u, 16 * u, 3 * u, 14 * u); lineTo(3 * u, 6 * u); quadraticTo(3 * u, 4 * u, 5 * u, 4 * u); close()
            },
            c, style = stroke(u),
        )
    }),

    /** Spot-light: a megaphone. */
    Megaphone({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(4 * u, 9.5f * u); lineTo(9 * u, 9.5f * u); lineTo(17 * u, 5 * u); lineTo(17 * u, 19 * u); lineTo(9 * u, 14.5f * u); lineTo(4 * u, 14.5f * u); close() }, c, style = stroke(u))
        drawLine(c, Offset(7 * u, 14.5f * u), Offset(8.5f * u, 20 * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(20.5f * u, 10 * u), Offset(20.5f * u, 14 * u), 2 * u, StrokeCap.Round)
    }),

    /** Proctor & HOD: two people. */
    People({ c ->
        val u = size.width / 24f
        drawCircle(c, 3.2f * u, Offset(9 * u, 8 * u), style = stroke(u))
        drawPath(Path().apply { moveTo(2.5f * u, 20 * u); quadraticTo(9 * u, 10 * u, 15.5f * u, 20 * u) }, c, style = stroke(u))
        drawCircle(c, 2.6f * u, Offset(17 * u, 7.5f * u), style = stroke(u))
        drawPath(Path().apply { moveTo(16 * u, 13 * u); quadraticTo(20 * u, 13 * u, 21.5f * u, 18 * u) }, c, style = stroke(u))
    }),

    /** Bonafide: a page with a seal. */
    Certificate({ c ->
        val u = size.width / 24f
        drawRoundRect(c, Offset(5 * u, 3 * u), Size(14 * u, 18 * u), CornerRadius(1.6f * u), style = stroke(u))
        drawLine(c, Offset(8.5f * u, 7.5f * u), Offset(15.5f * u, 7.5f * u), 2 * u, StrokeCap.Round)
        drawCircle(c, 3 * u, Offset(12 * u, 14.5f * u), style = stroke(u))
    }),

    /** Course feedback: a pencil. */
    Pencil({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(4 * u, 20 * u); lineTo(5 * u, 15.5f * u); lineTo(15.5f * u, 5 * u); lineTo(19 * u, 8.5f * u); lineTo(8.5f * u, 19 * u); close() }, c, style = stroke(u))
        drawLine(c, Offset(13 * u, 7.5f * u), Offset(16.5f * u, 11 * u), 2 * u)
    }),

    /** Paper seeing: an eye. */
    Eye({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(2.5f * u, 12 * u); quadraticTo(12 * u, 2 * u, 21.5f * u, 12 * u); quadraticTo(12 * u, 22 * u, 2.5f * u, 12 * u); close() }, c, style = stroke(u))
        drawCircle(c, 3 * u, Offset(12 * u, 12 * u), style = stroke(u))
    }),

    /** Receipts: a till slip with a torn foot. */
    Receipt({ c ->
        val u = size.width / 24f
        drawPath(
            Path().apply {
                moveTo(5 * u, 3 * u); lineTo(19 * u, 3 * u); lineTo(19 * u, 21 * u); lineTo(16.7f * u, 19 * u); lineTo(14.3f * u, 21 * u)
                lineTo(12 * u, 19 * u); lineTo(9.7f * u, 21 * u); lineTo(7.3f * u, 19 * u); lineTo(5 * u, 21 * u); close()
            },
            c, style = stroke(u),
        )
        drawLine(c, Offset(8.5f * u, 8 * u), Offset(15.5f * u, 8 * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(8.5f * u, 12 * u), Offset(13 * u, 12 * u), 2 * u, StrokeCap.Round)
    }),

    /** Gate log: an arched gate. */
    Gate({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(4 * u, 21 * u); lineTo(4 * u, 9 * u); quadraticTo(12 * u, 1 * u, 20 * u, 9 * u); lineTo(20 * u, 21 * u) }, c, style = stroke(u))
        drawLine(c, Offset(9.5f * u, 8 * u), Offset(9.5f * u, 21 * u), 2 * u, StrokeCap.Round)
        drawLine(c, Offset(14.5f * u, 8 * u), Offset(14.5f * u, 21 * u), 2 * u, StrokeCap.Round)
    }),

    /** Activities: a cup. */
    Trophy({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(7 * u, 4 * u); lineTo(17 * u, 4 * u); lineTo(17 * u, 9 * u); quadraticTo(17 * u, 15 * u, 12 * u, 15 * u); quadraticTo(7 * u, 15 * u, 7 * u, 9 * u); close() }, c, style = stroke(u))
        drawLine(c, Offset(12 * u, 15 * u), Offset(12 * u, 19.5f * u), 2 * u)
        drawLine(c, Offset(8 * u, 20 * u), Offset(16 * u, 20 * u), 2 * u, StrokeCap.Round)
        drawPath(Path().apply { moveTo(7 * u, 6 * u); quadraticTo(3 * u, 6 * u, 4 * u, 9 * u); quadraticTo(5 * u, 11 * u, 7.5f * u, 11 * u) }, c, style = stroke(u, 1.6f))
        drawPath(Path().apply { moveTo(17 * u, 6 * u); quadraticTo(21 * u, 6 * u, 20 * u, 9 * u); quadraticTo(19 * u, 11 * u, 16.5f * u, 11 * u) }, c, style = stroke(u, 1.6f))
    }),

    /** Course names: a tag. */
    Tag({ c ->
        val u = size.width / 24f
        drawPath(Path().apply { moveTo(3 * u, 12 * u); lineTo(11 * u, 4 * u); lineTo(20 * u, 4 * u); lineTo(20 * u, 13 * u); lineTo(12 * u, 21 * u); close() }, c, style = stroke(u))
        drawCircle(c, 1.7f * u, Offset(15.5f * u, 8.5f * u))
    }),

    /** Downloads: an arrow into a tray. */
    Download({ c ->
        val u = size.width / 24f
        drawLine(c, Offset(12 * u, 3 * u), Offset(12 * u, 15 * u), 2 * u, StrokeCap.Round)
        drawPath(Path().apply { moveTo(7 * u, 10 * u); lineTo(12 * u, 15 * u); lineTo(17 * u, 10 * u) }, c, style = stroke(u))
        drawPath(Path().apply { moveTo(4 * u, 15 * u); lineTo(4 * u, 20 * u); lineTo(20 * u, 20 * u); lineTo(20 * u, 15 * u) }, c, style = stroke(u))
    }),

    /** Display card: a framed picture. */
    Picture({ c ->
        val u = size.width / 24f
        drawRoundRect(c, Offset(3 * u, 5 * u), Size(18 * u, 14 * u), CornerRadius(1.6f * u), style = stroke(u))
        drawPath(Path().apply { moveTo(3.5f * u, 17 * u); lineTo(9 * u, 11.5f * u); lineTo(13.5f * u, 16 * u); lineTo(16 * u, 13.5f * u); lineTo(20.5f * u, 17.5f * u) }, c, style = stroke(u))
        drawCircle(c, 1.7f * u, Offset(16 * u, 9 * u))
    }),
}

private fun stroke(u: Float, w: Float = 2f) = Stroke(w * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun IconGlyph(icon: RisoIcon, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) { icon.draw(this, color) }
}

data class NavItem(val label: String, val icon: RisoIcon)

/**
 * Bottom tabs. Each tab is a full-height 72 dp target; the selected one sits in a solid
 * ink-B pill with the ink-A plate behind it, the same build as [InkButton].
 * Labels never truncate: they're short, and sized to fit five across at 360 dp.
 */
@Composable
fun RisoNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    val crowded = items.size > 4
    val pillWidth = if (crowded) 54.dp else 64.dp
    if (inks.editorial) {
        EditorialNavBar(items, selected, onSelect, modifier)
        return
    }
    Column(modifier.fillMaxWidth().background(if (inks.soft) inks.surface else inks.paper)) {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (crowded) 2.dp else 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val iconColor by animateColorAsState(if (on && !inks.sticker) inks.paper else inks.text, label = "icon")
                Column(
                    Modifier
                        .weight(1f)
                        .height(72.dp)
                        .clickable(role = Role.Tab, onClick = { onSelect(i) })
                        .semantics { this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val pill = RoundedCornerShape(50)
                    Box(contentAlignment = Alignment.Center) {
                        if (on) {
                            Box(
                                Modifier.size(pillWidth, 34.dp).offset(3.dp, 3.dp).clip(pill).background(if (inks.soft) androidx.compose.ui.graphics.Color.Transparent else if (inks.glass) inks.glassShadow else if (inks.sticker) inks.text else inks.a),
                            )
                            Box(
                                Modifier.size(pillWidth, 34.dp).clip(pill).background(if (inks.soft) inks.text else if (inks.sticker || inks.glass) inks.a else inks.b)
                                    .then(if (inks.sticker) Modifier.border(1.5.dp, inks.text, pill) else Modifier),
                            )
                        } else {
                            Spacer(Modifier.size(pillWidth, 34.dp))
                        }
                        IconGlyph(item.icon, iconColor)
                    }
                    Spacer(Modifier.height(5.dp))
                    BasicText(
                        item.label,
                        maxLines = 1,
                        style = RisoType.body.copy(
                            fontSize = if (crowded) 11.sp else 12.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = inks.text,
                            textAlign = TextAlign.Center,
                            letterSpacing = if (crowded) (-0.3).sp else (-0.2).sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Editorial tabs: a firm rule along the top with a red bar that slides to the open tab;
 * the open tab's name in bold, the rest quiet. No pills, nothing that isn't needed.
 */
@Composable
private fun EditorialNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val inks = LocalInks.current
    val slide by androidx.compose.animation.core.animateFloatAsState(
        selected.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "tab",
    )
    Column(modifier.fillMaxWidth().background(inks.paper)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .drawBehind {
                    drawRect(inks.text, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                    val w = size.width / items.size
                    drawRect(inks.a, topLeft = Offset(w * slide + w * 0.22f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.56f, size.height))
                },
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val tint by animateColorAsState(if (on) inks.text else inks.muted, label = "tabInk")
                Column(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clickable(role = Role.Tab, onClick = { onSelect(i) })
                        .semantics { this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconGlyph(item.icon, tint, Modifier.size(22.dp))
                    Spacer(Modifier.height(5.dp))
                    BasicText(
                        item.label.uppercase(),
                        maxLines = 1,
                        style = RisoType.label.copy(fontSize = 9.5.sp, letterSpacing = 0.08.em, color = tint, fontWeight = if (on) FontWeight.Black else FontWeight.Medium),
                    )
                }
            }
        }
    }
}
