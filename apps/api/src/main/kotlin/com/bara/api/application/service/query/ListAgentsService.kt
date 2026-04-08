package com.bara.api.application.service.query

import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Service

@Service
class ListAgentsService(
    private val agentRepository: AgentRepository,
) : ListAgentsQuery {

    override fun listAll(): List<Agent> = agentRepository.findAll()
}
