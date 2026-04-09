package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegistryAgentService(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) : RegistryAgentUseCase {

    override fun registry(providerId: String, agentName: String) {
        val agent = agentRepository.findByName(agentName)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentOwnershipException()
        }

        agentRegistryPort.register(agentName, agent.id)

        WideEvent.put("agent_id", agent.id)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_registry")
        WideEvent.message("Agent registry 완료")
    }
}
