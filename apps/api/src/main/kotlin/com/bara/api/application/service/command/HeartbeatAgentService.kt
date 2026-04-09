package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotRegisteredException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class HeartbeatAgentService(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) : HeartbeatAgentUseCase {

    override fun heartbeat(providerId: String, agentName: String) {
        if (!agentRegistryPort.isRegistered(agentName)) {
            throw AgentNotRegisteredException(agentName)
        }

        val agent = agentRepository.findByName(agentName)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentOwnershipException()
        }

        agentRegistryPort.refreshTtl(agentName)

        WideEvent.put("agent_id", agent.id)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "heartbeat_refreshed")
        WideEvent.message("Heartbeat TTL 갱신")
    }
}
