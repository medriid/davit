package dev.medriid.davit.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Overprinted display text (`.over` in tokens.css): a solid ink-A plate, with an ink-B
 * halftone plate printed over it a few dp out of register. Both multiply, so where the
 * plates overlap the paper takes the darker third colour.
 *
 * On first composition, and whenever [registerKey] changes, the top plate starts at
 * [from] and springs to [rest]. Pressing nudges the plate the other
 * way, like the web hover. With animations off it simply sits at [rest].
 */
@Composable
fun Overprint(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    plateA: Color = LocalInks.current.a,
    plateB: Color = LocalInks.current.b,
    rest: DpOffset = RisoMotion.Misreg,
    from: DpOffset = RisoMotion.RegisterFrom,
    dot: Dp = RisoMotion.Dot,
    registerKey: Any? = Unit,
    style: TextStyle = RisoType.display(fontSize),
) {
    // Soft: just the number, light and large, in the text colour.
    if (LocalInks.current.soft || LocalInks.current.editorial) {
        BasicText(text, modifier, style = style.copy(color = LocalInks.current.text))
        return
    }
    val motion = rememberMotionEnabled()
    val dots = rememberHalftoneBrush(plateB, cell = dot, dotRatio = 0.28f)
    val plate = remember { Animatable(if (motion) from else rest, DpOffset.VectorConverter) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(registerKey, motion) {
        if (!motion) {
            plate.snapTo(rest)
            return@LaunchedEffect
        }
        plate.snapTo(from)
        delay(200)
        plate.animateTo(rest, RisoMotion.register())
    }

    val shown = text.uppercase()
    Box(
        modifier.pointerInput(motion) {
            if (!motion) return@pointerInput
            detectTapGestures(onPress = {
                scope.launch { plate.animateTo(DpOffset((-6).dp, 10.dp), RisoMotion.quick()) }
                tryAwaitRelease()
                scope.launch { plate.animateTo(rest, RisoMotion.register()) }
            })
        },
    ) {
        BasicText(
            shown,
            style = style.copy(color = plateA),
            overflow = TextOverflow.Visible,
            modifier = Modifier.trimToCaps(style.fontSize).multiply(),
        )
        BasicText(
            shown,
            style = style.copy(brush = dots),
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .clearAndSetSemantics { }
                .trimToCaps(style.fontSize)
                .multiply()
                // shift in the draw step, not with graphicsLayer: a layer clipped the plate
                .drawWithContent {
                    translate(plate.value.x.toPx(), plate.value.y.toPx()) { this@drawWithContent.drawContent() }
                },
        )
    }
}
