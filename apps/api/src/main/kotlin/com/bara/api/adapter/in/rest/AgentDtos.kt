package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard

// ── Request ──

data class RegisterAgentRequest(
    val name: String,
    val agentCard: AgentCardRequest,
) {
    fun toCommand(): RegisterAgentCommand = RegisterAgentCommand(
        name = name,
        agentCard = agentCard.toDomain(),
    )
}

data class AgentCardRequest(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilitiesRequest = AgentCapabilitiesRequest(),
    val skills: List<AgentSkillRequest>,
    val iconUrl: String? = null,
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
        skills = skills.map {
            AgentCard.AgentSkill(
                id = it.id, name = it.name, description = it.description,
                tags = it.tags, examples = it.examples,
            )
        },
        iconUrl = iconUrl,
    )
}

data class AgentCapabilitiesRequest(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
)

data class AgentSkillRequest(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

// ── Response ──

data class AgentResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent): AgentResponse = AgentResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            createdAt = agent.createdAt.toString(),
        )
    }
}

data class AgentDetailResponse(
    val id: String,
    val name: String,
    val providerId: String,
    val agentCard: AgentCard,
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent): AgentDetailResponse = AgentDetailResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            agentCard = agent.agentCard,
            createdAt = agent.createdAt.toString(),
        )
    }
}

data class AgentListResponse(val agents: List<AgentResponse>)

data class ErrorResponse(val error: String, val message: String)
