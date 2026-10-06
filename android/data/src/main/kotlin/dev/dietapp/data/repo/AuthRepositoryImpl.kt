package dev.dietapp.data.repo

import dev.dietapp.data.domain.Lang.t
import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.ProfileRow
import dev.dietapp.data.di.AppScope
import dev.dietapp.data.local.AppMode
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.local.BodyStore
import dev.dietapp.data.local.SecretStore
import dev.dietapp.data.media.PhotoThumbs
import dev.dietapp.data.net.AppError
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.RequestCodeBody
import dev.dietapp.data.net.SessionStore
import dev.dietapp.data.net.VerifyBody
import dev.dietapp.data.net.apiCall
import dev.dietapp.data.net.apiCallUnit
import dev.dietapp.data.net.toAppError
import dev.dietapp.data.sync.OutboxFiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/** Runs [block], turning every failure into an [AppError] with a message that can be shown as is. */
internal suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e.toAppError())
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val api: DietApi,
    private val session: SessionStore,
    private val files: OutboxFiles,
    private val sync: SyncTrigger,
    private val json: Json,
    private val modeStore: ModeStore,
    private val secrets: SecretStore,
    private val thumbs: PhotoThumbs,
    private val body: BodyStore,
    @AppScope scope: CoroutineScope,
) : AuthRepository {

    private fun signedIn(token: Boolean, mode: AppMode?) = mode == AppMode.Local || (mode == AppMode.Server && token)

    /** Signed in means: local mode chosen, or server mode with a token. */
    override val loggedIn: StateFlow<Boolean> = combine(session.loggedIn, modeStore.mode, ::signedIn)
        .stateIn(scope, SharingStarted.Eagerly, signedIn(session.loggedIn.value, modeStore.mode.value))

    override suspend fun requestCode(email: String): Result<Unit> = guarded {
        apiCallUnit(json) { api.requestCode(RequestCodeBody(email.trim())) }
    }

    override suspend fun verify(email: String, code: String): Result<Unit> = guarded {
        val address = email.trim().lowercase()
        val token = apiCall(json) { api.verify(VerifyBody(address, code.trim())) }
        val previous = db.profile().get()?.email
        if (previous != null && !previous.equals(address, ignoreCase = true)) wipeLocalData() // another account
        session.save(token.accessToken, address)
        modeStore.set(AppMode.Server)
        val me = apiCall(json) { api.me() }
        db.profile().upsert(ProfileRow(email = me.email, calorieGoal = me.calorieGoal))
        sync.requestSync(pull = true)
    }

    override suspend fun useWithoutServer() {
        db.profile().upsert(ProfileRow(email = null, calorieGoal = null))
        modeStore.set(AppMode.Local)
    }

    override suspend fun logout() {
        session.clear()
        secrets.clear()
        wipeLocalData()
        modeStore.set(null)
    }

    private suspend fun wipeLocalData() {
        db.withTransaction {
            db.entries().clear()
            db.weights().clear()
            db.outbox().clear()
            db.notes().clear()
            db.messages().clear()
            db.foods().clear()
            db.profile().clear()
        }
        files.clear()
        thumbs.clear()
        body.clear() // what the person said about themselves goes with the diary
    }
}
