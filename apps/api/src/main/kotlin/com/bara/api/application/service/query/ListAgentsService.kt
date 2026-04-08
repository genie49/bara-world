package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class ListAgentsService(
    private val agentRepository: AgentRepository,
) : ListAgentsQuery {

    override fun listAll(): List<Agent> {
        val agents = agentRepository.findAll()
        WideEvent.put("agent_count", agents.size.toString())
        WideEvent.put("outcome", "agents_listed")
        WideEvent.message("Agent 목록 조회 성공")
        return agents
    }
}
