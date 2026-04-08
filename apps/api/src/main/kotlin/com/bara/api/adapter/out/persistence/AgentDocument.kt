package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "agents")
@CompoundIndex(name = "provider_name_idx", def = "{'providerId': 1, 'name': 1}", unique = true)
data class AgentDocument(
    @Id val id: String,
    val name: String,
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
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesDocument,
    val skills: List<AgentSkillDocument>,
    val iconUrl: String?,
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
        defaultInputModes = defaultInputModes,
        defaultOutputModes = defaultOutputModes,
        capabilities = AgentCard.AgentCapabilities(
            streaming = capabilities.streaming,
            pushNotifications = capabilities.pushNotifications,
        ),
        skills = skills.map { it.toDomain() },
        iconUrl = iconUrl,
    )

    companion object {
        fun fromDomain(c: AgentCard): AgentCardDocument = AgentCardDocument(
            name = c.name,
            description = c.description,
            version = c.version,
            defaultInputModes = c.defaultInputModes,
            defaultOutputModes = c.defaultOutputModes,
            capabilities = AgentCapabilitiesDocument(
                streaming = c.capabilities.streaming,
                pushNotifications = c.capabilities.pushNotifications,
            ),
            skills = c.skills.map { AgentSkillDocument.fromDomain(it) },
            iconUrl = c.iconUrl,
        )
    }
}

data class AgentCapabilitiesDocument(
    val streaming: Boolean,
    val pushNotifications: Boolean,
)

data class AgentSkillDocument(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String>,
) {
    fun toDomain(): AgentCard.AgentSkill = AgentCard.AgentSkill(
        id = id, name = name, description = description,
        tags = tags, examples = examples,
    )

    companion object {
        fun fromDomain(s: AgentCard.AgentSkill): AgentSkillDocument = AgentSkillDocument(
            id = s.id, name = s.name, description = s.description,
            tags = s.tags, examples = s.examples,
        )
    }
}
