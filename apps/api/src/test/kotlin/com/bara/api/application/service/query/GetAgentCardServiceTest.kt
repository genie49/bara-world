package com.bara.api.application.service.query

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class GetAgentCardServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = GetAgentCardService(agentRepository)

    @Test
    fun `존재하는 Agent의 AgentCard를 반환한다`() {
        val card = AgentCard(
            name = "Test Agent",
            description = "A test agent",
            version = "1.0.0",
        )
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = card)
        every { agentRepository.findById(agent.id) } returns agent

        val result = service.getCardById(agent.id)

        assertEquals("Test Agent", result.name)
    }

    @Test
    fun `존재하지 않는 Agent의 Card 조회 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.getCardById("not-exist")
        }
    }
}
