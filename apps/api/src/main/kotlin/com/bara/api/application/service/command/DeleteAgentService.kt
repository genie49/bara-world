package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class DeleteAgentService(
    private val agentRepository: AgentRepository,
) : DeleteAgentUseCase {

    override fun delete(providerId: String, agentId: String) {
        val agent = agentRepository.findById(agentId)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentNotFoundException()
        }

        agentRepository.deleteById(agentId)

        WideEvent.put("agent_id", agentId)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "agent_deleted")
        WideEvent.message("Agent 삭제 완료")
    }
}
