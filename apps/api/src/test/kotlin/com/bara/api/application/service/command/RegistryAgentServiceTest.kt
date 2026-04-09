package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RegistryAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val service = RegistryAgentService(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")

    @Test
    fun `소유한 Agent를 registry하면 Redis에 등록된다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.register("my-agent", agent.id) }

        service.registry("p-1", "my-agent")

        verify { agentRegistryPort.register("my-agent", agent.id) }
    }

    @Test
    fun `존재하지 않는 Agent registry 시 예외`() {
        every { agentRepository.findByName("unknown") } returns null

        assertThrows<AgentNotFoundException> {
            service.registry("p-1", "unknown")
        }
    }

    @Test
    fun `소유하지 않은 Agent registry 시 예외`() {
        val agent = Agent.create(name = "other-agent", providerId = "p-other", agentCard = agentCard)
        every { agentRepository.findByName("other-agent") } returns agent

        assertThrows<AgentOwnershipException> {
            service.registry("p-1", "other-agent")
        }
    }

    @Test
    fun `이미 registry된 Agent도 멱등하게 성공한다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.register("my-agent", agent.id) }

        service.registry("p-1", "my-agent")
        service.registry("p-1", "my-agent")

        verify(exactly = 2) { agentRegistryPort.register("my-agent", agent.id) }
    }
}
