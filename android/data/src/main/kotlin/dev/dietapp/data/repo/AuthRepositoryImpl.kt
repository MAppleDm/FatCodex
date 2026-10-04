package dev.dietapp.data.repo

import dev.dietapp.data.domain.Lang.t
import androidx.room.withTransaction
import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.db.ProfileRow
import dev.dietapp.data.di.AppScope
import dev.dietapp.data.domain.CalorieGoal
import dev.dietapp.data.local.AppMode
import dev.dietapp.data.local.ModeStore
import dev.dietapp.data.local.SecretStore
import dev.dietapp.data.net.AppError
import dev.dietapp.data.net.DietApi
import dev.dietapp.data.net.GoalBody
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

val GOAL_TOO_LOW_MESSAGE get() = t("Цель ниже 1 200 ккал в день без наблюдения врача не рекомендуется. Выбери не меньше 1 200.",
    "A goal below 1,200 kcal a day is not recommended without a doctor's supervision. Choose 1,200 or more.")
val GOAL_TOO_HIGH_MESSAGE get() = t("Цель выше 6 000 ккал похожа на опечатку.", "A goal above 6,000 kcal looks like a typo.")

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

    override suspend fun setGoal(goal: Int): Result<Unit> = guarded {
        when (CalorieGoal.check(goal)) {
            CalorieGoal.Check.TooLow -> throw AppError(GOAL_TOO_LOW_MESSAGE, "goal_too_low")
            CalorieGoal.Check.TooHigh -> throw AppError(GOAL_TOO_HIGH_MESSAGE, "goal_too_high")
            CalorieGoal.Check.Ok -> Unit
        }
        if (modeStore.isLocal) {
            db.profile().upsert(ProfileRow(email = null, calorieGoal = goal))
            return@guarded
        }
        val me = apiCall(json) { api.setGoal(GoalBody(goal)) }
        db.profile().upsert(ProfileRow(email = me.email, calorieGoal = me.calorieGoal))
    }

    override suspend fun useWithoutServer() {
        val goal = db.profile().get()?.calorieGoal // a goal set earlier on this phone survives
        db.profile().upsert(ProfileRow(email = null, calorieGoal = goal))
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
    }
}
