package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.AgentCard
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class GetAgentCardService(
    private val agentRepository: AgentRepository,
) : GetAgentCardQuery {

    override fun getCardById(agentId: String): AgentCard {
        WideEvent.put("agent_id", agentId)
        val agent = agentRepository.findById(agentId)
            ?: throw AgentNotFoundException()
        WideEvent.put("outcome", "agent_card_retrieved")
        WideEvent.message("Agent Card 조회 성공")
        return agent.agentCard
    }
}
