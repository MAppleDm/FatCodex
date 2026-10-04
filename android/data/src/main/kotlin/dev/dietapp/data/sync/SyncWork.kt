package dev.dietapp.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dietapp.data.di.AppScope
import dev.dietapp.data.repo.SyncTrigger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The fallback path: runs when the network is back after an attempt failed, even if the app was closed. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (engine.sync(pull = true)) {
        SyncResult.Retry -> Result.retry()
        SyncResult.Done, SyncResult.SignedOut -> Result.success()
    }
}

/**
 * Tries right away (the common case: online, the answer arrives in seconds) and only falls back to
 * WorkManager when that attempt could not reach the server.
 */
@Singleton
class WorkManagerSyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SyncEngine,
    @AppScope private val scope: CoroutineScope,
) : SyncTrigger {
    override fun requestSync(pull: Boolean) {
        scope.launch {
            if (engine.sync(pull) == SyncResult.Retry) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * The trigger the rest of the app uses: with a server it pushes/pulls through [WorkManagerSyncTrigger];
 * in local mode there is nothing to send anywhere, so it just works through the queued messages on the phone.
 */
@Singleton
class AppSyncTrigger @Inject constructor(
    private val mode: dev.dietapp.data.local.ModeStore,
    private val server: WorkManagerSyncTrigger,
    private val local: dev.dietapp.data.local.LocalEngine,
    @AppScope private val scope: CoroutineScope,
) : SyncTrigger {
    override fun requestSync(pull: Boolean) {
        when (mode.mode.value) {
            dev.dietapp.data.local.AppMode.Local -> scope.launch { local.run() }
            dev.dietapp.data.local.AppMode.Server -> server.requestSync(pull)
            null -> Unit
        }
    }
}
