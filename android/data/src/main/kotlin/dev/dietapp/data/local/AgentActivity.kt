package dev.dietapp.data.local

import dev.dietapp.data.domain.AgentStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What the agent is doing right now, per message being read. Lives in memory only: it describes the next few seconds, so
 * there is nothing to keep, and a message that is not being read has no entry.
 */
@Singleton
class AgentActivity @Inject constructor() {
    private val current = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())

    /** Message id to its current step. */
    val statuses: StateFlow<Map<String, AgentStatus>> = current

    fun report(messageId: String, status: AgentStatus) = current.update { if (it[messageId] == status) it else it + (messageId to status) }

    fun clear(messageId: String) = current.update { if (messageId in it) it - messageId else it }
}
