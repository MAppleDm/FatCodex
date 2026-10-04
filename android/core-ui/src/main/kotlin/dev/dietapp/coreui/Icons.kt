package dev.dietapp.coreui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * Icons are drawn as thin strokes instead of pulling in an icon library:
 * it keeps the APK small and every glyph has the same 1.5dp weight.
 */

private fun DrawScope.stroke() = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun MicIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawRoundRect(color, Offset(w * 0.375f, w * 0.12f), Size(w * 0.25f, w * 0.45f), CornerRadius(w * 0.125f), style = stroke())
    val arc = Path().apply {
        moveTo(w * 0.22f, w * 0.46f)
        cubicTo(w * 0.22f, w * 0.78f, w * 0.78f, w * 0.78f, w * 0.78f, w * 0.46f)
    }
    drawPath(arc, color, style = stroke())
    drawLine(color, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.88f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}

@Composable
fun CameraIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawRoundRect(color, Offset(w * 0.1f, w * 0.26f), Size(w * 0.8f, w * 0.55f), CornerRadius(w * 0.1f), style = stroke())
    drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, w * 0.535f), style = stroke())
    drawLine(color, Offset(w * 0.34f, w * 0.26f), Offset(w * 0.4f, w * 0.17f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.4f, w * 0.17f), Offset(w * 0.6f, w * 0.17f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.6f, w * 0.17f), Offset(w * 0.66f, w * 0.26f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}

@Composable
fun SendIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawLine(color, Offset(w * 0.5f, w * 0.84f), Offset(w * 0.5f, w * 0.18f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    val head = Path().apply {
        moveTo(w * 0.24f, w * 0.44f)
        lineTo(w * 0.5f, w * 0.18f)
        lineTo(w * 0.76f, w * 0.44f)
    }
    drawPath(head, color, style = stroke())
}

/** Three dots: the "more" corner icon for settings and history. */
@Composable
fun MoreIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    for (i in 0..2) drawCircle(color, radius = 1.6.dp.toPx(), center = Offset(w * 0.5f, w * (0.25f + 0.25f * i)))
}

@Composable
fun BackIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    val chevron = Path().apply {
        moveTo(w * 0.62f, w * 0.2f)
        lineTo(w * 0.32f, w * 0.5f)
        lineTo(w * 0.62f, w * 0.8f)
    }
    drawPath(chevron, color, style = stroke())
}

@Composable
fun CloseIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(24.dp)) {
    val w = size.width
    drawLine(color, Offset(w * 0.25f, w * 0.25f), Offset(w * 0.75f, w * 0.75f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.75f, w * 0.25f), Offset(w * 0.25f, w * 0.75f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
}
