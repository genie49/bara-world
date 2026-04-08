package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.AgentCard
import org.springframework.stereotype.Service

@Service
class GetAgentCardService(
    private val agentRepository: AgentRepository,
) : GetAgentCardQuery {

    override fun getCardById(agentId: String): AgentCard {
        val agent = agentRepository.findById(agentId)
            ?: throw AgentNotFoundException()
        return agent.agentCard
    }
}
