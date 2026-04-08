package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class GetAgentService(
    private val agentRepository: AgentRepository,
) : GetAgentQuery {

    override fun getById(agentId: String): Agent {
        WideEvent.put("agent_id", agentId)
        val agent = agentRepository.findById(agentId) ?: throw AgentNotFoundException()
        WideEvent.put("outcome", "agent_retrieved")
        WideEvent.message("Agent 조회 성공")
        return agent
    }
}
