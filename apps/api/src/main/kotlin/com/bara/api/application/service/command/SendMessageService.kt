package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SendMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
) : SendMessageUseCase {

    override fun sendMessage(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): String {
        val agentId = agentRegistryPort.getAgentId(agentName)
            ?: throw AgentUnavailableException()

        val taskId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()

        val taskMessage = mapOf(
            "task_id" to taskId,
            "context_id" to request.contextId,
            "user_id" to userId,
            "request_id" to requestId,
            "result_topic" to "results.api",
            "message" to mapOf(
                "message_id" to request.messageId,
                "role" to "user",
                "parts" to listOf(mapOf("text" to request.text)),
            ),
        )

        taskPublisherPort.publish(agentId, taskMessage)

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("outcome", "task_published")
        WideEvent.message("태스크 발행 완료")

        return taskId
    }
}
