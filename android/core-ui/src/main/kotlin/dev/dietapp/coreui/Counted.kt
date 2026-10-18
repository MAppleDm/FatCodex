package dev.dietapp.coreui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A number as it is on screen at this moment. [value] counts up or down to where the real number went; [glow] is 1 as the
 * count starts and fades to 0 as it settles, for a touch of colour that says "this just changed".
 */
class Counted(val value: Int, val glow: Float)

/** Quick at the start and slow into the last digits, like a counter coming to rest. */
private val CountEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** A small step is over in a blink, a whole meal takes a little longer, nothing takes more than a second. */
fun countDurationMs(delta: Float): Int = (260 + 110 * ln(1 + abs(delta) / 8f)).toInt().coerceIn(260, 900)

/**
 * [target] as a number that counts to its new value instead of jumping. It shows the target at once when it first appears and
 * whenever [resetKey] changes (another day, the first load): only a change of the same day's number is counted.
 * A new target in the middle of a count carries on from where the number is, so quick changes never jerk.
 */
@Composable
fun rememberCounted(target: Int, resetKey: Any? = null): Counted {
    val count = remember(resetKey) { Animatable(target.toFloat()) }
    val glow = remember(resetKey) { Animatable(0f) }
    LaunchedEffect(target, resetKey) {
        val delta = target - count.value
        if (abs(delta) < 0.5f) return@LaunchedEffect
        val ms = countDurationMs(delta)
        launch {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(ms + 400, easing = LinearOutSlowInEasing))
        }
        count.animateTo(target.toFloat(), tween(ms, easing = CountEasing))
    }
    return Counted(count.value.roundToInt(), glow.value)
}
