package dev.dietapp.coreui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Press feedback uses the palette's quiet pressed surface. */
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

/**
 * "1 420 / 1 900 ккал" with the day's date pressed to the right edge, over "Б 90 · Ж 50 · У 140": two lines so that a
 * long figure never breaks the macros apart. The date is there folded and open. Tap to expand.
 *
 * The numbers count to their new value when a food is recorded, changed or removed, with a touch of orange that fades as
 * they settle ([rememberCounted]); [details] gets the count of the calories so what it shows follows it. [resetKey] says when a
 * number is not the same day's number any more (another day, the first load): then it appears at once, uncounted.
 */
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
    date: String? = null,
    resetKey: Any? = null,
    details: @Composable ColumnScope.(shownKcal: Int) -> Unit = {},
) {
    val c = AppTheme.colors
    val mono = SpanStyle(fontFeatureSettings = "tnum")
    val shownKcal = rememberCounted(kcal, resetKey)
    val shownProtein = rememberCounted(protein, resetKey)
    val shownFat = rememberCounted(fat, resetKey)
    val shownCarbs = rememberCounted(carbs, resetKey)
    fun counted(base: Color, n: Counted) = mono.copy(color = lerp(base, c.time, n.glow))
    val energy = buildAnnotatedString {
        withStyle(counted(c.foreground, shownKcal).copy(fontSize = 32.sp)) { append(formatInt(shownKcal.value)) }
        withStyle(mono.copy(color = c.secondary, fontSize = 14.sp)) {
            if (goal != null) append(" / ${formatInt(goal)}")
            append(" ${CoreTexts.kcal}")
        }
    }
    val macros = buildAnnotatedString {
        append("${CoreTexts.p} ")
        withStyle(counted(c.secondary, shownProtein)) { append(formatInt(shownProtein.value)) }
        append(" · ${CoreTexts.f} ")
        withStyle(counted(c.secondary, shownFat)) { append(formatInt(shownFat.value)) }
        append(" · ${CoreTexts.c} ")
        withStyle(counted(c.secondary, shownCarbs)) { append(formatInt(shownCarbs.value)) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(120))
            .tap(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Give the numbers the full line on a small screen or with larger text; a date must not orphan the unit.
            val stackDate = maxWidth / LocalDensity.current.fontScale < 300.dp
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(energy, style = AppTheme.type.body.copy(lineHeight = 40.sp), modifier = Modifier.weight(1f, fill = false))
                    if (date != null && !stackDate) SummaryDate(date, Modifier.padding(start = 12.dp, top = 4.dp))
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(macros, style = AppTheme.type.secondary.copy(fontSize = 14.sp))
                    if (date != null && stackDate) SummaryDate(date)
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            details(shownKcal.value)
        }
    }
}

@Composable
private fun SummaryDate(date: String, modifier: Modifier = Modifier) {
    Crossfade(date, animationSpec = tween(180), modifier = modifier, label = "date") {
        Text(it, style = AppTheme.type.caption)
    }
}

/** "к сегодняшнему дню" under the date of the summary: shown only while looking at a day that is not today. */
@Composable
fun DayLine(action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
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
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = AppTheme.type.secondary)
        Text(value, style = AppTheme.type.mono.copy(color = if (valueColor == Color.Unspecified) AppTheme.colors.foreground else valueColor))
    }
}

// ---------- feed rows ----------

/**
 * One food in the feed. Recorded food (counted in the day) is tinted green, with the time it was recorded in orange under its name;
 * food still waiting for the user's "записать" is grey, not counted, and has no time yet.
 */
@Composable
fun EntryRow(
    name: String,
    grams: Double,
    kcal: Double?,
    modifier: Modifier = Modifier,
    approximate: Boolean = false,
    pending: Boolean = false,
    /** When it was recorded, "08:15": small and orange, at the left under the name. Only a recorded food has it. */
    time: String? = null,
    /** A dish: null for a plain food, otherwise whether its ingredients are shown; a chevron after the name opens them. */
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
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
            // the chevron sits right after the name, so that the numbers stay in their column with and without it
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = t.body.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (expanded != null) {
                    Box(
                        Modifier.size(36.dp).tap(onClick = onToggle)
                            .semantics { contentDescription = CoreTexts.parts; role = Role.Button }.testTag("parts-toggle"),
                        contentAlignment = Alignment.Center,
                    ) { ChevronIcon(AppTheme.colors.secondary, up = expanded) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("${formatGrams(grams)} ${CoreTexts.grams}", style = t.monoSecondary)
            Text(kcalText, style = t.mono.copy(color = color), textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
        if (time != null && !pending) {
            Text(
                time, style = t.monoSecondary.copy(fontSize = 12.sp, lineHeight = 16.sp, color = AppTheme.colors.time),
                modifier = Modifier.testTag("entry-time"),
            )
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

/** The app's side of the chat: a bubble at the left, under the user's message it answers. [wide] for a card with choices. */
@Composable
fun AppBubble(modifier: Modifier = Modifier, wide: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = if (wide) 12.dp else 48.dp, top = 3.dp, bottom = 3.dp)) {
        Column(
            Modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier)
                .clip(AppBubbleShape)
                .background(c.surface)
                .border(1.dp, c.border, AppBubbleShape)
                .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            content = content,
        )
    }
}

private val AppBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
private val UserBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)

/**
 * A line from the app in the feed, in its bubble: a reply, what it changed, a question it asked. An error is plain red text
 * (a tap dismisses it), not something the app says.
 */
@Composable
fun FeedNote(text: String, tone: NoteTone, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = AppTheme.colors
    if (tone == NoteTone.Error) {
        val click = if (onClick != null) Modifier.tap(onClick = onClick) else Modifier
        Text(text, style = AppTheme.type.secondary.copy(color = c.error), modifier = modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    AppBubble(modifier, onClick = onClick) {
        val lines = text.lines()
        Text(lines.first(), style = AppTheme.type.body.copy(color = if (tone == NoteTone.Question) c.foreground else c.secondary))
        lines.drop(1).forEach { Text(it, style = AppTheme.type.caption) }
    }
}

/**
 * What the user sent, in a bubble on the right, so it never reads as part of the answer below it. A gap above it starts a
 * new turn of the conversation. [status] shows while it waits ("…") or when it failed; [progress] is what the agent is
 * doing with it right now, one quiet mono line under it (it is longer than a status and changes, so it does not share the
 * message's line).
 */
@Composable
fun MessageRow(
    text: String,
    status: String?,
    isError: Boolean,
    modifier: Modifier = Modifier,
    /** False for the user's answer to a question in the middle of a turn: no gap above it. */
    newTurn: Boolean = true,
    /** A photo message: its small picture, in a rounded rectangle above the words (if there are any). */
    thumbnail: ImageBitmap? = null,
    thumbnailDescription: String? = null,
    progress: String? = null,
    onClick: () -> Unit,
) {
    val t = AppTheme.type
    val c = AppTheme.colors
    Column(
        modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 56.dp, end = 12.dp, top = if (newTurn) 14.dp else 3.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (status != null) {
                Text(status, style = t.monoSecondary.copy(color = if (isError) c.error else c.secondary), modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(
                Modifier.weight(1f, fill = false).clip(UserBubbleShape).background(c.pressed)
                    .padding(if (thumbnail != null && text.isEmpty()) 4.dp else 0.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (thumbnail != null) {
                    // the longer side is at most 160dp, whatever the shape of the photo
                    val ratio = (thumbnail.width.toFloat() / thumbnail.height).coerceIn(0.5f, 2f)
                    Image(
                        bitmap = thumbnail, contentDescription = thumbnailDescription, contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(if (text.isEmpty()) 0.dp else 4.dp)
                            .then(if (ratio >= 1f) Modifier.width(160.dp) else Modifier.height(160.dp))
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(15.dp))
                            .testTag("message-thumb"),
                    )
                }
                if (text.isNotEmpty()) {
                    Text(
                        text,
                        style = t.body.copy(color = if (isError) c.error else c.foreground),
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
        if (progress != null) {
            Text(progress, style = t.monoSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 4.dp).testTag("progress"))
        }
    }
}

/**
 * A row that can be swiped to the right to delete it: the row follows the finger over a quiet red "[label]", and past a
 * third of its width (or flung) it goes. Screen readers get the same as an action of the row.
 */
@Composable
fun SwipeToDelete(label: String, onDelete: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val drag = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, width.toFloat())) } }
    Box(
        modifier.fillMaxWidth().onSizeChanged { width = it.width }.clipToBounds()
            .semantics { customActions = listOf(CustomAccessibilityAction(label) { onDelete(); true }) },
    ) {
        if (offset.value > 0f) {
            Row(
                Modifier.matchParentSize().background(c.error.copy(alpha = if (c.isDark) 0.22f else 0.12f)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(label, style = AppTheme.type.secondary.copy(color = c.error)) }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.background(c.background).testTag("swipe")
                .draggable(
                    state = drag, orientation = Orientation.Horizontal, enabled = enabled,
                    onDragStopped = { velocity ->
                        if (width > 0 && (offset.value > width / 3f || (velocity > 2000f && offset.value > 24f))) {
                            offset.animateTo(width.toFloat(), tween(140))
                            onDelete()
                        } else {
                            offset.animateTo(0f, tween(160))
                        }
                    },
                ),
        ) { content() }
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
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().background(c.background).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
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
