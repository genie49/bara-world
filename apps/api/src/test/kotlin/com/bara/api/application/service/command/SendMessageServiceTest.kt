package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.AgentUnavailableException
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

class SendMessageServiceTest {

    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val taskPublisherPort = mockk<TaskPublisherPort>()
    private val service = SendMessageService(agentRegistryPort, taskPublisherPort)

    @Test
    fun `살아있는 Agent에 메시지를 보내면 Kafka에 발행된다`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        justRun { taskPublisherPort.publish(eq("agent-001"), any<com.bara.api.application.port.out.TaskMessagePayload>()) }

        val request = SendMessageUseCase.SendMessageRequest(
            messageId = "msg-1",
            text = "안녕하세요",
            contextId = "ctx-1",
        )
        val taskId = service.sendMessage("user-1", "my-agent", request)

        assertNotNull(taskId)
        verify { taskPublisherPort.publish(eq("agent-001"), any<com.bara.api.application.port.out.TaskMessagePayload>()) }
    }

    @Test
    fun `registry되지 않은 Agent에 메시지를 보내면 예외`() {
        every { agentRegistryPort.getAgentId("dead-agent") } returns null

        val request = SendMessageUseCase.SendMessageRequest(
            messageId = "msg-1",
            text = "hello",
            contextId = null,
        )

        assertThrows<AgentUnavailableException> {
            service.sendMessage("user-1", "dead-agent", request)
        }
    }
}
