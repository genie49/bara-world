package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeleteAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = DeleteAgentService(agentRepository)

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
    )

    @Test
    fun `본인 소유 Agent를 삭제하면 성공한다`() {
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent
        justRun { agentRepository.deleteById(agent.id) }

        service.delete("p-1", agent.id)

        verify { agentRepository.deleteById(agent.id) }
    }

    @Test
    fun `존재하지 않는 Agent 삭제 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.delete("p-1", "not-exist")
        }
    }

    @Test
    fun `다른 Provider의 Agent 삭제 시 예외 발생`() {
        val agent = Agent.create(name = "My Agent", providerId = "p-other", agentCard = agentCard)
        every { agentRepository.findById(agent.id) } returns agent

        assertThrows<AgentNotFoundException> {
            service.delete("p-1", agent.id)
        }
    }
}
