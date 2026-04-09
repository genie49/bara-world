package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "agents")
data class AgentDocument(
    @Id val id: String,
    @Indexed(unique = true) val name: String,
    @Indexed val providerId: String,
    val agentCard: AgentCardDocument,
    val createdAt: java.time.Instant,
) {
    fun toDomain(): Agent = Agent(
        id = id,
        name = name,
        providerId = providerId,
        agentCard = agentCard.toDomain(),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(a: Agent): AgentDocument = AgentDocument(
            id = a.id,
            name = a.name,
            providerId = a.providerId,
            agentCard = AgentCardDocument.fromDomain(a.agentCard),
            createdAt = a.createdAt,
        )
    }
}

data class AgentCardDocument(
    val name: String,
    val description: String,
    val version: String,
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
    )

    companion object {
        fun fromDomain(c: AgentCard): AgentCardDocument = AgentCardDocument(
            name = c.name,
            description = c.description,
            version = c.version,
        )
    }
}
