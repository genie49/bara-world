package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotRegisteredException
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
import java.time.Instant

class HeartbeatAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val service = HeartbeatAgentService(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")
    private val agent = Agent(
        id = "a-1", name = "my-agent", providerId = "p-1",
        agentCard = agentCard, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `등록된 Agent의 heartbeat는 TTL을 갱신한다`() {
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.refreshTtl("my-agent") }

        service.heartbeat("p-1", "my-agent")

        verify { agentRegistryPort.refreshTtl("my-agent") }
    }

    @Test
    fun `미등록 Agent의 heartbeat는 AgentNotRegisteredException 발생`() {
        every { agentRegistryPort.isRegistered("unknown") } returns false

        assertThrows<AgentNotRegisteredException> {
            service.heartbeat("p-1", "unknown")
        }
    }

    @Test
    fun `존재하지 않는 Agent는 AgentNotFoundException 발생`() {
        every { agentRegistryPort.isRegistered("ghost") } returns true
        every { agentRepository.findByName("ghost") } returns null

        assertThrows<AgentNotFoundException> {
            service.heartbeat("p-1", "ghost")
        }
    }

    @Test
    fun `소유권 불일치 시 AgentOwnershipException 발생`() {
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        every { agentRepository.findByName("my-agent") } returns agent

        assertThrows<AgentOwnershipException> {
            service.heartbeat("p-other", "my-agent")
        }
    }
}
