package dev.dietapp.data.local

import dev.dietapp.data.db.AppDatabase
import dev.dietapp.data.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Removes, at every start, the lines that the first versions wrote under a message that the model could not read ("Нет связи
 * с моделью. Разобрано без неё"). The app no longer reads anything without the agent, so those lines are not true, and they
 * would stay in the chat of their day for good: only an error line can be tapped away. Costs one query.
 */
@Singleton
class LegacyNotes @Inject constructor(db: AppDatabase, @AppScope scope: CoroutineScope) {
    /** Finishes when the outdated lines are gone. */
    val cleanup: Job = scope.launch { runCatching { db.notes().deleteLegacyFallback() } }
}
