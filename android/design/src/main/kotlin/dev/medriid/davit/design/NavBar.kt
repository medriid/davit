package dev.medriid.davit.design

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
 * Labels never truncate: they're short, and sized to fit four across at 360 dp.
 */
@Composable
fun RisoNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val inks = LocalInks.current
    Column(modifier.fillMaxWidth().background(inks.paper)) {
        Rule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val iconColor by animateColorAsState(if (on) inks.paper else inks.text, label = "icon")
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
                                Modifier.size(64.dp, 34.dp).offset(3.dp, 3.dp).clip(pill).background(inks.a),
                            )
                            Box(Modifier.size(64.dp, 34.dp).clip(pill).background(inks.b))
                        } else {
                            Spacer(Modifier.size(64.dp, 34.dp))
                        }
                        IconGlyph(item.icon, iconColor)
                    }
                    Spacer(Modifier.height(5.dp))
                    BasicText(
                        item.label,
                        maxLines = 1,
                        style = RisoType.body.copy(
                            fontSize = 12.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = inks.text,
                            textAlign = TextAlign.Center,
                            letterSpacing = (-0.2).sp,
                        ),
                    )
                }
            }
        }
    }
}
