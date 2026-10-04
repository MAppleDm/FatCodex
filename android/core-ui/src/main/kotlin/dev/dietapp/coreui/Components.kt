package dev.dietapp.coreui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Press feedback is a flat grey wash, not a ripple. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    background(if (pressed && enabled) AppTheme.colors.pressed else Color.Transparent)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.border))
}

@Composable
fun IconAction(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val c = AppTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) c.foreground else Color.Transparent)
            .tap(onClick = onClick)
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { content(if (active) c.background else c.foreground) }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AppTheme.colors.foreground) {
    Text(
        text,
        style = AppTheme.type.secondary.copy(color = color),
        modifier = modifier.tap(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** A text field that is just a line underneath. */
@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    mono: Boolean = false,
    secret: Boolean = false,
) {
    val t = AppTheme.type
    val style = if (mono) t.mono else t.body
    Column(modifier) {
        Box(Modifier.padding(vertical = 8.dp)) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = AppTheme.colors.tertiary))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = style,
                singleLine = singleLine,
                cursorBrush = SolidColor(AppTheme.colors.foreground),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hairline()
    }
}

// ---------- day summary ----------

/** "1 420 / 1 900 ккал · Б 90 · Ж 50 · У 140". Tap to expand. */
@Composable
fun DaySummary(
    kcal: Int,
    goal: Int?,
    protein: Int,
    fat: Int,
    carbs: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    val mono = SpanStyle(fontFamily = FontFamily.Monospace)
    val line = buildAnnotatedString {
        withStyle(mono) { append(formatInt(kcal)) }
        if (goal != null) {
            append(" / ")
            withStyle(mono) { append(formatInt(goal)) }
        }
        append(" ${CoreTexts.kcal} · ${CoreTexts.p} ")
        withStyle(mono) { append(formatInt(protein)) }
        append(" · ${CoreTexts.f} ")
        withStyle(mono) { append(formatInt(fat)) }
        append(" · ${CoreTexts.c} ")
        withStyle(mono) { append(formatInt(carbs)) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(120))
            .tap(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(line, style = AppTheme.type.body.copy(fontSize = 15.sp))
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            details()
        }
    }
}

/** "29 сентября        к сегодняшнему дню": shown only while looking at a day that is not today. */
@Composable
fun DayLine(day: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day, style = AppTheme.type.secondary)
        TextAction(action, onAction)
    }
}

/** Two-pixel progress line, used for calories against the goal. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Box(modifier.fillMaxWidth().height(2.dp).background(c.border)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(c.foreground))
    }
}

@Composable
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AppTheme.type.secondary)
        Text(value, style = AppTheme.type.mono)
    }
}

// ---------- feed rows ----------

/**
 * One food in the feed. Recorded food (counted in the day) is tinted green; food still waiting for the user's
 * "записать" is grey and not counted. [note] is a short line under it: what the user picked when asked about it.
 */
@Composable
fun EntryRow(
    name: String,
    grams: Double,
    kcal: Double?,
    modifier: Modifier = Modifier,
    approximate: Boolean = false,
    pending: Boolean = false,
    note: String? = null,
    onClick: () -> Unit,
) {
    val t = AppTheme.type
    val color = if (pending) AppTheme.colors.secondary else AppTheme.colors.recorded
    val kcalText = when {
        kcal == null -> "—"
        approximate -> "~" + formatInt(kcal)
        else -> formatInt(kcal)
    }
    Column(modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = t.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text("${formatGrams(grams)} ${CoreTexts.grams}", style = t.monoSecondary)
            Text(kcalText, style = t.mono.copy(color = color), textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
        if (note != null) {
            Text("› $note", style = t.caption.copy(color = AppTheme.colors.tertiary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * An opened food. [header] is its row as in the feed (a tap on it folds the food back), then what this portion holds
 * ([facts]: "316 ккал · Б 12 · Ж 2 · У 62"), where the numbers come from ([source]), the amount and the name to
 * correct, and the conversation about it ([history]). No fill of its own: on an OLED screen it is as black as the
 * rest, set apart by hairlines.
 */
@Composable
fun EntryEditor(
    name: String,
    grams: String,
    onName: (String) -> Unit,
    onGrams: (String) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    facts: String? = null,
    source: String? = null,
    history: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        header()
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            if (facts != null) Text(facts, style = AppTheme.type.body, modifier = Modifier.testTag("entry-facts"))
            if (source != null) Text(source, style = AppTheme.type.caption, modifier = Modifier.padding(top = 2.dp))
            UnderlineField(name, onName, placeholder = CoreTexts.name, imeAction = ImeAction.Next)
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    grams, onGrams, modifier = Modifier.width(96.dp).testTag("entry-grams"), placeholder = CoreTexts.gramsHint,
                    keyboardType = KeyboardType.Decimal, onImeAction = onDone, mono = true,
                )
                Text(" ${CoreTexts.grams}", style = AppTheme.type.monoSecondary)
                Spacer(Modifier.weight(1f))
                TextAction(CoreTexts.delete, onDelete, Modifier.testTag("entry-delete"), color = AppTheme.colors.error)
            }
            history()
        }
        Hairline()
    }
}

enum class NoteTone { Question, Info, Error }

/** A line from the app in the feed: a clarifying question, a notice, an error. */
@Composable
fun FeedNote(text: String, tone: NoteTone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    val color = if (tone == NoteTone.Error) c.error else c.secondary
    val click = if (onClick != null) Modifier.tap(onClick = onClick) else Modifier
    Row(modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (tone == NoteTone.Question) {
            Box(Modifier.width(1.dp).height(20.dp).background(c.foreground))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = AppTheme.type.secondary.copy(color = if (tone == NoteTone.Question) c.foreground else color))
    }
}

/**
 * What the user sent, on the right, so it never reads as part of the answer below it. A gap above it starts a new
 * turn of the conversation. [status] shows while it waits ("…") or when it failed.
 */
@Composable
fun MessageRow(
    text: String,
    status: String?,
    isError: Boolean,
    modifier: Modifier = Modifier,
    /** False for the user's answer to a question in the middle of a turn: no gap above it. */
    newTurn: Boolean = true,
    onClick: () -> Unit,
) {
    val t = AppTheme.type
    Row(
        modifier.fillMaxWidth().tap(onClick = onClick)
            .padding(start = 56.dp, end = 16.dp, top = if (newTurn) 16.dp else 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (status != null) {
            Text(status, style = t.monoSecondary.copy(color = if (isError) AppTheme.colors.error else AppTheme.colors.secondary))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            style = t.body.copy(color = AppTheme.colors.secondary, textAlign = TextAlign.End),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier, extra: String? = null, onClick: () -> Unit = {}) {
    val t = AppTheme.type
    Row(
        modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = t.body, modifier = Modifier.weight(1f))
        Text(value, style = t.mono)
        if (extra != null) {
            Spacer(Modifier.width(12.dp))
            Text(extra, style = t.monoSecondary)
        }
    }
}

// ---------- weight chart ----------

/**
 * Mini chart with no axes and no grid: light dots for the raw weigh-ins, a solid line for the trend.
 * [raw] and [trend] are aligned by index (oldest first).
 */
@Composable
fun Sparkline(raw: List<Double>, trend: List<Double>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val all = raw + trend
        if (all.isEmpty()) return@Canvas
        val pad = 4.dp.toPx()
        val lo = all.min()
        val range = (all.max() - lo).coerceAtLeast(0.5)
        fun x(i: Int, n: Int) = if (n <= 1) size.width / 2 else pad + (size.width - 2 * pad) * i / (n - 1)
        fun y(v: Double) = (size.height - pad - ((v - lo) / range * (size.height - 2 * pad))).toFloat()

        raw.forEachIndexed { i, v -> drawCircle(c.tertiary, 2.dp.toPx(), Offset(x(i, raw.size), y(v))) }
        if (trend.size == 1) {
            drawCircle(c.foreground, 2.5.dp.toPx(), Offset(x(0, 1), y(trend[0])))
        } else {
            for (i in 1 until trend.size) {
                drawLine(c.foreground, Offset(x(i - 1, trend.size), y(trend[i - 1])), Offset(x(i, trend.size), y(trend[i])),
                    strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

// ---------- input bar ----------

/**
 * The only input of the app. Two icons, no captions: camera, and microphone (which becomes "send"
 * as soon as there is text).
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    listening: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    sendDescription: String = CoreTexts.send,
    micDescription: String = CoreTexts.mic,
    cameraDescription: String = CoreTexts.camera,
) {
    val c = AppTheme.colors
    val hasText = value.isNotBlank()
    Column(modifier.fillMaxWidth().background(c.background)) {
        Hairline()
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                if (value.isEmpty()) Text(placeholder, style = AppTheme.type.body.copy(color = c.tertiary))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = AppTheme.type.body,
                    maxLines = 4,
                    cursorBrush = SolidColor(c.foreground),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (hasText) onSend() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                )
            }
            IconAction(onCamera, cameraDescription) { CameraIcon(it) }
            if (hasText) {
                IconAction(onSend, sendDescription) { SendIcon(it) }
            } else {
                IconAction(onMic, micDescription, active = listening) { MicIcon(it) }
            }
        }
    }
}

/** A 1dp-bordered box, used for the camera shutter. */
@Composable
fun ShutterButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val color = Color.White
    Box(
        modifier.size(68.dp).clip(CircleShape).border(1.5.dp, color, CircleShape).padding(6.dp)
            .clip(CircleShape).background(color.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription; role = Role.Button },
    )
}
