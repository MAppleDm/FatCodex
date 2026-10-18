package dev.dietapp.data.domain

/** What the agent is doing with a message. Only the kind of step is kept: never what was asked or read. */
enum class AgentStep { Reading, Looking, Thinking, SearchBase, SearchWeb, ReadPage, Proposing, Adding, Removing, Replying, Recording }

/** One step of the agent, shown as a quiet line under the message while it works. [detail] is the site being read, if any. */
data class AgentStatus(val step: AgentStep, val detail: String? = null)
