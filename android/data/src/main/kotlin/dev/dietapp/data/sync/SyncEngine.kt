package dev.dietapp.data.sync

import dev.dietapp.data.domain.Lang.t
import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.NoteRow
import dev.dietapp.data.db.OutboxRow
import dev.dietapp.data.db.ProfileRow
import dev.dietapp.data.db.toRow
import dev.dietapp.data.net.ApiException
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.EntryPatchBody
import dev.dietapp.data.net.MessageBody
import dev.dietapp.data.net.MessageResultDto
import dev.dietapp.data.net.NetworkUnavailableException
import dev.dietapp.data.net.OFFLINE_MESSAGE
import dev.dietapp.data.net.PendingQuestionBody
import dev.dietapp.data.net.SessionStore
import dev.dietapp.data.net.WeightBody
import dev.dietapp.data.net.apiCall
import dev.dietapp.data.net.apiCallUnit
import java.time.Clock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

sealed interface SyncResult {
    /** Nothing left to do (or nothing more we can do: a rejected item is marked, not retried forever). */
    data object Done : SyncResult

    /** Offline or the server is busy: try again later. Nothing was lost. */
    data object Retry : SyncResult

    /** The token was refused. The session has been cleared. */
    data object SignedOut : SyncResult
}

/**
 * Local-first synchronisation. Room is the source of truth for the UI; this class moves changes both ways:
 *
 *  1. outbox messages -> POST /v1/messages (oldest first, stops at the first temporary failure so that
 *     "масла было меньше" is never applied before the meal it refers to),
 *  2. locally edited / deleted entries and weights -> PATCH/PUT/DELETE,
 *  3. optionally pull GET /v1/sync (rows with unsent local edits are never overwritten).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: AppDatabase,
    private val api: DietApi,
    private val session: SessionStore,
    private val files: OutboxFiles,
    private val json: Json,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val _confirmations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Emits once each time a message has been turned into diary entries (drives the haptic tick). */
    val confirmations: SharedFlow<Unit> = _confirmations.asSharedFlow()

    suspend fun sync(pull: Boolean): SyncResult = mutex.withLock {
        if (session.token == null) return@withLock SyncResult.SignedOut
        try {
            pushOutbox()
            pushEntries()
            pushWeights()
            if (pull) pullAll()
            SyncResult.Done
        } catch (e: ApiException) {
            when {
                e.isUnauthorized -> {
                    session.clear()
                    SyncResult.SignedOut
                }
                e.isRetryable -> SyncResult.Retry
                else -> SyncResult.Done
            }
        } catch (e: NetworkUnavailableException) {
            SyncResult.Retry
        }
    }

    // ---------- 1. messages ----------

    private suspend fun pushOutbox() {
        for (row in db.outbox().queued()) {
            val image = if (row.hasImage) files.read(row.id) else null
            if (row.hasImage && image == null) {
                db.outbox().upsert(row.copy(state = "failed", error = t("Фото не найдено на устройстве.", "The photo is no longer on the phone.")))
                continue
            }
            val body = MessageBody(
                clientId = row.id,
                text = row.text,
                imageBase64 = image?.let { Base64.getEncoder().encodeToString(it) },
                imageMime = row.imageMime,
                day = row.day,
                eatenAt = row.eatenAt,
                source = row.source,
                pendingQuestion = row.pendingQuestion?.let { PendingQuestionBody(it, row.pendingTargetId) },
            )
            val result = try {
                apiCall(json) { api.postMessage(body) }
            } catch (e: ApiException) {
                when {
                    e.isUnauthorized -> throw e
                    e.isRetryable -> {
                        db.outbox().upsert(row.copy(error = e.message, attempts = row.attempts + 1))
                        throw e
                    }
                    else -> { // the server understood and refused: show why, keep going with the rest
                        db.outbox().upsert(row.copy(state = "failed", error = e.message, attempts = row.attempts + 1))
                        continue
                    }
                }
            } catch (e: NetworkUnavailableException) {
                db.outbox().upsert(row.copy(error = OFFLINE_MESSAGE, attempts = row.attempts + 1))
                throw e
            }
            applyMessageResult(row, result)
            files.delete(row.id)
            _confirmations.tryEmit(Unit)
        }
    }

    private suspend fun applyMessageResult(row: OutboxRow, result: MessageResultDto) = db.withTransaction {
        db.entries().upsert(result.entries.map { it.toRow(dirty = false) })
        result.removedIds.forEach { db.entries().markDeleted(it, dirty = false) }
        val now = clock.millis()
        when {
            result.clarifyQuestion != null -> db.notes().insert(
                NoteRow(day = row.day, kind = "question", text = result.clarifyQuestion,
                    targetEntryId = result.clarifyTargetId, resolved = false, createdAtMs = now, messageId = row.id),
            )
            result.entries.isEmpty() && result.removedIds.isEmpty() -> db.notes().insert(
                NoteRow(day = row.day, kind = "info", text = dev.dietapp.data.local.NO_FOOD_NOTE, targetEntryId = null,
                    resolved = true, createdAtMs = now, messageId = row.id),
            )
        }
        db.outbox().delete(row.id)
    }

    // ---------- 2. local edits ----------

    private suspend fun pushEntries() {
        for (row in db.entries().dirty()) {
            try {
                if (row.deleted) {
                    apiCallUnit(json, okStatuses = setOf(404)) { api.deleteEntry(row.id) }
                    settleEntry(row, null)
                } else {
                    val dto = apiCall(json) { api.patchEntry(row.id, EntryPatchBody(grams = row.grams, name = row.name)) }
                    settleEntry(row, dto.toRow(dirty = false))
                }
            } catch (e: ApiException) {
                when {
                    e.isUnauthorized || e.isRetryable -> throw e
                    e.status == 404 -> settleEntry(row, row.copy(deleted = true, dirty = false)) // gone on the server
                    else -> settleEntry(row, null) // refused: the next pull brings the server's version back
                }
            }
        }
    }

    /** Mark [pushed] as sent, unless the user edited it again while the request was in flight. */
    private suspend fun settleEntry(pushed: dev.dietapp.data.db.EntryRow, server: dev.dietapp.data.db.EntryRow?) =
        db.withTransaction {
            val current = db.entries().get(pushed.id) ?: return@withTransaction
            if (current != pushed) return@withTransaction
            db.entries().upsert(server ?: current.copy(dirty = false))
        }

    private suspend fun pushWeights() {
        for (row in db.weights().dirty()) {
            try {
                if (row.deleted) {
                    apiCallUnit(json, okStatuses = setOf(404)) { api.deleteWeight(row.id) }
                    settleWeight(row, null)
                } else {
                    val dto = apiCall(json) { api.putWeight(row.id, WeightBody(row.day, row.kg)) }
                    settleWeight(row, dto.toRow(dirty = false))
                }
            } catch (e: ApiException) {
                if (e.isUnauthorized || e.isRetryable) throw e
                settleWeight(row, null)
            }
        }
    }

    private suspend fun settleWeight(pushed: dev.dietapp.data.db.WeightRow, server: dev.dietapp.data.db.WeightRow?) =
        db.withTransaction {
            val current = db.weights().get(pushed.id) ?: return@withTransaction
            if (current != pushed) return@withTransaction
            db.weights().upsert(server ?: current.copy(dirty = false))
        }

    // ---------- 3. pull ----------

    private suspend fun pullAll() {
        var since = session.since
        while (true) {
            val page = apiCall(json) { api.sync(since) }
            db.withTransaction {
                val dirtyEntries = db.entries().dirtyIds().toSet()
                val dirtyWeights = db.weights().dirtyIds().toSet()
                db.entries().upsert(page.entries.filter { it.id !in dirtyEntries }.map { it.toRow(dirty = false) })
                db.weights().upsert(page.weights.filter { it.id !in dirtyWeights }.map { it.toRow(dirty = false) })
                db.profile().upsert(ProfileRow(email = session.email, calorieGoal = page.calorieGoal))
            }
            val progressed = page.nextSince != since
            since = page.nextSince
            session.since = since
            if (!page.hasMore || !progressed) break
        }
    }
}
