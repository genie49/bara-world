package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class HeartbeatConsumerTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val consumer = HeartbeatConsumer(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")

    @Test
    fun `registry된 Agent의 heartbeat는 TTL을 갱신한다`() {
        val agent = Agent.create(name = "my-agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        justRun { agentRegistryPort.refreshTtl("my-agent") }

        consumer.handleHeartbeat("""{"agent_id":"${agent.id}","timestamp":"2026-04-09T10:00:00Z"}""")

        verify { agentRegistryPort.refreshTtl("my-agent") }
    }

    @Test
    fun `registry되지 않은 Agent의 heartbeat는 무시한다`() {
        val agent = Agent.create(name = "unregistered", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        every { agentRegistryPort.isRegistered("unregistered") } returns false

        consumer.handleHeartbeat("""{"agent_id":"${agent.id}","timestamp":"2026-04-09T10:00:00Z"}""")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }

    @Test
    fun `존재하지 않는 Agent의 heartbeat는 무시한다`() {
        every { agentRepository.findById("unknown-id") } returns null

        consumer.handleHeartbeat("""{"agent_id":"unknown-id","timestamp":"2026-04-09T10:00:00Z"}""")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }

    @Test
    fun `잘못된 JSON은 무시한다`() {
        consumer.handleHeartbeat("not json")

        verify(exactly = 0) { agentRegistryPort.refreshTtl(any()) }
    }
}
