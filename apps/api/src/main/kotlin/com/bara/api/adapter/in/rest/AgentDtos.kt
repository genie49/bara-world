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
) {
    fun toDomain(): AgentCard = AgentCard(
        name = name,
        description = description,
        version = version,
    )
}

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
