package com.bara.api.application.port.`in`.command

import com.bara.api.domain.model.AgentCard

data class RegisterAgentCommand(
    val name: String,
    val agentCard: AgentCard,
)
