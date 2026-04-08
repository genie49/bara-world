package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Service

@Service
class GetAgentService(
    private val agentRepository: AgentRepository,
) : GetAgentQuery {

    override fun getById(agentId: String): Agent =
        agentRepository.findById(agentId) ?: throw AgentNotFoundException()
}
