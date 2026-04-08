package com.bara.api.domain.model

import java.time.Instant
import java.util.UUID

data class Agent(
    val id: String,
    val name: String,
    val providerId: String,
    val agentCard: AgentCard,
    val createdAt: Instant,
) {
    companion object {
        fun create(name: String, providerId: String, agentCard: AgentCard): Agent =
            Agent(
                id = UUID.randomUUID().toString(),
                name = name,
                providerId = providerId,
                agentCard = agentCard,
                createdAt = Instant.now(),
            )
    }
}
