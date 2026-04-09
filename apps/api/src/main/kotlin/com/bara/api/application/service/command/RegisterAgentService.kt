package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.model.Agent
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegisterAgentService(
    private val agentRepository: AgentRepository,
) : RegisterAgentUseCase {

    override fun register(providerId: String, command: RegisterAgentCommand): Agent {
        agentRepository.findByName(command.name)?.let {
            throw AgentNameAlreadyExistsException()
        }

        val agent = Agent.create(
            name = command.name,
            providerId = providerId,
            agentCard = command.agentCard,
        )

        val saved = agentRepository.save(agent)

        WideEvent.put("agent_id", saved.id)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_registered")
        WideEvent.message("Agent 등록 완료")

        return saved
    }
}
