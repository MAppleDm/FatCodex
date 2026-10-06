package dev.dietapp.ui.body

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dietapp.Texts
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.Hairline
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.formatInt
import dev.dietapp.coreui.tap
import dev.dietapp.data.domain.Activity
import dev.dietapp.data.domain.CalorieGoal
import dev.dietapp.data.domain.DailyGoal
import dev.dietapp.data.domain.EnergyEstimate
import dev.dietapp.data.domain.Lang
import dev.dietapp.data.domain.Sex
import java.util.Locale
import kotlin.math.roundToInt

/** A choice among a few, drawn as a box: the picked one has a heavier frame and is announced as selected. */
@Composable
internal fun ChoiceBox(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val c = AppTheme.colors
    Column(
        modifier
            .border(if (selected) 2.dp else 1.dp, if (selected) c.foreground else c.border)
            .tap(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label, style = AppTheme.type.body)
        if (detail != null) Text(detail, style = AppTheme.type.caption, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Man or woman, two big boxes. Nothing is picked until the person picks. */
@Composable
fun SexChoice(selected: Sex?, onSelect: (Sex) -> Unit, modifier: Modifier = Modifier, tag: String = "sex") {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceBox(Texts.ME_MALE, selected == Sex.Male, { onSelect(Sex.Male) }, Modifier.weight(1f).testTag("$tag-male"))
        ChoiceBox(Texts.ME_FEMALE, selected == Sex.Female, { onSelect(Sex.Female) }, Modifier.weight(1f).testTag("$tag-female"))
    }
}

/** The five levels of activity, each with a plain description of what it looks like in a week. */
@Composable
fun ActivityChoice(selected: Activity?, onSelect: (Activity) -> Unit, modifier: Modifier = Modifier, tag: String = "activity") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Activity.entries.forEach { a ->
            ChoiceBox(
                Texts.activityName(a), selected == a, { onSelect(a) },
                Modifier.fillMaxWidth().testTag("$tag-${a.name.lowercase()}"), detail = Texts.activityHint(a),
            )
        }
    }
}

/**
 * A number to dial in: minus and plus for exact steps, a slider for big ones, the value large in between. [value] null
 * means "not said yet": it shows a dash and the slider rests at [default] until the person touches it.
 */
@Composable
fun NumberStepper(
    value: Double?,
    default: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    decimals: Int = 0,
    /** On the page an unanswered number is a dash; in the questions it shows the value that "Далее" will take. */
    dashWhenEmpty: Boolean = true,
    /** A correction: "+300" and "−300" rather than "300" and "-300". */
    signed: Boolean = false,
) {
    val c = AppTheme.colors
    val shown = value ?: default
    val blank = value == null && dashWhenEmpty
    fun snap(v: Double) = (Math.round((v - min) / step) * step + min).coerceIn(min, max)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction({ onChange(snap(shown - step)) }, "−", Modifier.testTag("$tag-minus")) { Text("−", style = AppTheme.type.mono.copy(fontSize = 26.sp, color = it)) }
            Text(
                if (blank) "—" else "${if (signed) Texts.signedKcal(shown.roundToInt()) else number(shown, decimals)} $unit",
                style = AppTheme.type.mono.copy(fontSize = 30.sp, color = if (blank) c.tertiary else c.foreground, textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f).testTag("$tag-value"),
            )
            IconAction({ onChange(snap(shown + step)) }, "+", Modifier.testTag("$tag-plus")) { Text("+", style = AppTheme.type.mono.copy(fontSize = 26.sp, color = it)) }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { onChange(snap(it.toDouble())) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = c.foreground, activeTrackColor = c.foreground, inactiveTrackColor = c.border,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.testTag("$tag-slider"),
        )
    }
}

/** What a day costs: the figure large, how it was reached, and that it is an estimate. */
@Composable
fun EnergyBlock(estimate: EnergyEstimate?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("energy")) {
        if (estimate == null) {
            Text(Texts.ME_INCOMPLETE, style = AppTheme.type.secondary, modifier = Modifier.testTag("energy-incomplete"))
            return@Column
        }
        Text(Texts.ME_ENERGY, style = AppTheme.type.caption)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text("≈ ${formatInt(estimate.tdee)}", style = AppTheme.type.mono.copy(fontSize = 34.sp), modifier = Modifier.testTag("energy-tdee"))
            Text(" ${Texts.ME_KCAL_A_DAY}", style = AppTheme.type.monoSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(Texts.meRest(estimate.bmr, factor(estimate.factor)), style = AppTheme.type.secondary, modifier = Modifier.padding(top = 4.dp).testTag("energy-rest"))
        Text(Texts.ME_FORMULA, style = AppTheme.type.caption, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The goal of a day: what a day costs plus the person's own correction, which is dialled in here. It is never typed in.
 * Draws nothing until what a day costs is known.
 */
@Composable
fun GoalBlock(state: BodyUiState, onAdjustment: (Int) -> Unit, modifier: Modifier = Modifier) {
    val estimate = state.estimate ?: return
    val goal = state.goal ?: return
    Column(modifier.fillMaxWidth().testTag("goal-block")) {
        Text(Texts.ME_GOAL, style = AppTheme.type.caption)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(formatInt(goal), style = AppTheme.type.mono.copy(fontSize = 34.sp), modifier = Modifier.testTag("goal-value"))
            Text(" ${Texts.ME_KCAL_A_DAY}", style = AppTheme.type.monoSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            Texts.meGoalSum(estimate.tdee, state.adjustment), style = AppTheme.type.secondary,
            modifier = Modifier.padding(top = 4.dp).testTag("goal-sum"),
        )
        Text(Texts.ME_ADJUSTMENT, style = AppTheme.type.caption, modifier = Modifier.padding(top = 16.dp))
        NumberStepper(
            state.adjustment.toDouble(), 0.0, DailyGoal.MIN_ADJUSTMENT.toDouble(), DailyGoal.MAX_ADJUSTMENT.toDouble(),
            DailyGoal.ADJUSTMENT_STEP.toDouble(), Texts.KCAL, { onAdjustment(it.roundToInt()) },
            tag = "adjust", dashWhenEmpty = false, signed = true,
        )
        Text(Texts.ME_ADJUSTMENT_HINT, style = AppTheme.type.caption)
        if (state.goalLimited) {
            Text(Texts.meGoalFloor(CalorieGoal.MIN), style = AppTheme.type.secondary, modifier = Modifier.padding(top = 8.dp).testTag("goal-floor"))
        }
    }
}

/** A caption over a control, with a hairline above it, for the "О себе" page. */
@Composable
internal fun MeSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Text(title, style = AppTheme.type.caption, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 0.dp).padding(bottom = 12.dp)) { content() }
    }
}

/** "1,55" in Russian, "1.55" in English; no trailing zeros. */
internal fun factor(f: Double): String =
    String.format(Locale.ROOT, "%.3f", f).trimEnd('0').trimEnd('.').let { if (Lang.en) it else it.replace('.', ',') }

/** "30", "82,5" (a comma in Russian, a dot in English), whole numbers without a decimal part. */
internal fun number(v: Double, decimals: Int): String {
    val text = if (decimals == 0) v.roundToInt().toString() else String.format(Locale.ROOT, "%.${decimals}f", v).trimEnd('0').trimEnd('.')
    return if (Lang.en) text else text.replace('.', ',')
}
