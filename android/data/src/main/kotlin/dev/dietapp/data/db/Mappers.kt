package dev.dietapp.data.db

import dev.dietapp.data.domain.DaySummary
import dev.dietapp.data.domain.Entry
import dev.dietapp.data.domain.EntryStatus
import dev.dietapp.data.domain.Note
import dev.dietapp.data.domain.NoteKind
import dev.dietapp.data.domain.OutboxMessage
import dev.dietapp.data.domain.OutboxState
import dev.dietapp.data.domain.Per100
import dev.dietapp.data.domain.Profile
import dev.dietapp.data.domain.Source
import dev.dietapp.data.domain.Totals
import dev.dietapp.data.domain.Weight
import dev.dietapp.data.net.EntryDto
import dev.dietapp.data.net.WeightDto
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** The server sends UTC with a trailing Z; accept any ISO offset to be safe. */
internal fun parseInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal fun EntryDto.toRow(dirty: Boolean = false) = EntryRow(
    id = id,
    mealId = mealId,
    day = day,
    eatenAtMs = parseInstant(eatenAt).toEpochMilli(),
    position = position,
    name = name,
    grams = grams,
    kcal = kcal,
    protein = protein,
    fat = fat,
    carbs = carbs,
    kcal100 = per100?.kcal,
    protein100 = per100?.protein,
    fat100 = per100?.fat,
    carbs100 = per100?.carbs,
    foodName = foodName,
    status = status,
    confidence = confidence,
    source = source,
    updatedAtMs = parseInstant(updatedAt).toEpochMilli(),
    deleted = deleted,
    dirty = dirty,
)

internal fun WeightDto.toRow(dirty: Boolean = false) = WeightRow(
    id = id, day = day, kg = kg, updatedAtMs = parseInstant(updatedAt).toEpochMilli(), deleted = deleted, dirty = dirty,
)

fun EntryRow.toDomain() = Entry(
    id = id,
    mealId = mealId,
    day = LocalDate.parse(day),
    eatenAt = Instant.ofEpochMilli(eatenAtMs),
    position = position,
    name = name,
    grams = grams,
    kcal = kcal,
    protein = protein,
    fat = fat,
    carbs = carbs,
    per100 = if (kcal100 != null && protein100 != null && fat100 != null && carbs100 != null) {
        Per100(kcal100, protein100, fat100, carbs100)
    } else {
        null
    },
    foodName = foodName,
    status = EntryStatus.fromWire(status),
    confidence = confidence,
    source = Source.fromWire(source),
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    dirty = dirty,
    pending = pending,
)

fun Entry.toRow(deleted: Boolean = false) = EntryRow(
    id = id,
    mealId = mealId,
    day = day.toString(),
    eatenAtMs = eatenAt.toEpochMilli(),
    position = position,
    name = name,
    grams = grams,
    kcal = kcal,
    protein = protein,
    fat = fat,
    carbs = carbs,
    kcal100 = per100?.kcal,
    protein100 = per100?.protein,
    fat100 = per100?.fat,
    carbs100 = per100?.carbs,
    foodName = foodName,
    status = status.toWire(),
    confidence = confidence,
    source = source.toWire(),
    updatedAtMs = updatedAt.toEpochMilli(),
    deleted = deleted,
    dirty = dirty,
    pending = pending,
)

fun WeightRow.toDomain() = Weight(id, LocalDate.parse(day), kg, Instant.ofEpochMilli(updatedAtMs))

fun NoteRow.toDomain() = Note(
    id = id,
    day = LocalDate.parse(day),
    kind = when (kind) {
        "question" -> NoteKind.Question
        "error" -> NoteKind.Error
        "proposal" -> NoteKind.Proposal
        "answer" -> NoteKind.Answer
        "base_change" -> NoteKind.BaseChange
        else -> NoteKind.Info
    },
    text = text,
    targetEntryId = targetEntryId,
    createdAt = Instant.ofEpochMilli(createdAtMs),
    messageId = messageId,
    resolved = resolved,
    choices = if (kind == "proposal") dev.dietapp.data.local.ProposalCodec.decode(payload) else emptyList(),
)

fun MessageRow.toDomain() = dev.dietapp.data.domain.Message(id, LocalDate.parse(day), text, hasImage, Instant.ofEpochMilli(atMs), aboutEntryId,
    Instant.ofEpochMilli(createdAtMs))

fun FoodRow.toDomain() = dev.dietapp.data.domain.Food(
    id = id,
    name = name,
    aliases = aliases.lines().map { it.trim() }.filter { it.isNotEmpty() },
    per100 = dev.dietapp.data.domain.Per100(kcal, protein, fat, carbs),
    estimated = estimated,
    note = note,
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    createdAt = Instant.ofEpochMilli(createdAtMs),
    origin = origin,
    url = url,
)

fun OutboxRow.toDomain() = OutboxMessage(
    id = id,
    text = text,
    hasImage = hasImage,
    day = LocalDate.parse(day),
    createdAt = Instant.ofEpochMilli(createdAtMs),
    state = if (state == "failed") OutboxState.Failed else OutboxState.Queued,
    error = error,
    attempts = attempts,
)

fun ProfileRow?.toDomain(fallbackEmail: String?) = Profile(this?.email ?: fallbackEmail, this?.calorieGoal)

fun DaySummaryRow.toDomain() = DaySummary(LocalDate.parse(day), Totals(kcal, protein, fat, carbs))
