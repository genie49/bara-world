package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.AgentCard

interface GetAgentCardQuery {
    fun getCardById(agentId: String): AgentCard
}
