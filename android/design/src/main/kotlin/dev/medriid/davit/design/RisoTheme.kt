package dev.medriid.davit.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** Provides the ink pair to everything below. Wrap the root in [Grain] for paper + grain. */
@Composable
fun RisoTheme(inks: Inks = InkPairs.all.first(), content: @Composable () -> Unit) {
    // The type follows the look; set before anything below composes.
    RisoType.soft = inks.soft
    RisoType.editorial = inks.editorial
    CompositionLocalProvider(LocalInks provides inks, content = content)
}

/**
 * Plain text in the current ink: the text pull on paper; on a [TiltedCard], ink B
 * multiplied into the ink-A block. [label] uppercases the string and defaults to the
 * spaced label style.
 */
@Composable
fun InkText(
    text: String,
    modifier: Modifier = Modifier,
    label: Boolean = false,
    style: TextStyle = if (label) RisoType.label else RisoType.body,
    maxLines: Int = Int.MAX_VALUE,
) {
    val inks = LocalInks.current
    val onCard = LocalOnCard.current
    BasicText(
        if (label) text.uppercase() else text,
        modifier = if (onCard) modifier.multiply() else modifier,
        // A colour the caller set (an accent, a muted caption) wins; otherwise the text ink.
        style = style.copy(color = if (onCard) inks.b else if (style.color != androidx.compose.ui.graphics.Color.Unspecified) style.color else inks.text),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Static display heading in one ink, multiplied (`.sec-head h2`). Use [Overprint] for the
 * screen's single animated headline, and this for everything else.
 */
@Composable
fun Heading(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = LocalInks.current.b,
    uppercase: Boolean = true,
) {
    // Soft: plain sans in sentence case, in the text colour.
    val soft = LocalInks.current.soft || LocalInks.current.editorial
    BasicText(
        if (uppercase && !soft) text.uppercase() else text,
        modifier = if (soft) modifier else modifier.trimToCaps(fontSize).multiply(),
        style = RisoType.display(fontSize).copy(color = if (soft) LocalInks.current.text else color),
        overflow = TextOverflow.Visible,
    )
}
