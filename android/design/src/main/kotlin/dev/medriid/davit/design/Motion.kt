package dev.medriid.davit.design

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

object RisoMotion {
    /** Resting misregistration of the top plate (`--misreg-x/y`). */
    val Misreg = DpOffset(7.dp, 5.dp)

    /** Where the top plate starts before it springs into register (`@keyframes register`). */
    val RegisterFrom = DpOffset(26.dp, (-14).dp)

    /** Plates sliding into register: a soft overshoot that settles in about 450 ms. */
    fun <T> register() = spring<T>(dampingRatio = 0.42f, stiffness = 260f)

    /** Small UI moves: button plates, bars. */
    fun <T> quick() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    val Dot: Dp = 6.dp
}

/**
 * False when the system animation scale is 0 ("Remove animations").
 * Every riso animation checks this and snaps to its end state instead.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
