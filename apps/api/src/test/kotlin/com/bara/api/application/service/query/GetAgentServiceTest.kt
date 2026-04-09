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

class GetAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = GetAgentService(agentRepository)

    @Test
    fun `존재하는 Agent를 조회하면 반환한다`() {
        val card = AgentCard(
            name = "A",
            description = "d",
            version = "1.0.0",
        )
        val agent = Agent.create(name = "My Agent", providerId = "p-1", agentCard = card)
        every { agentRepository.findById(agent.id) } returns agent

        val result = service.getById(agent.id)

        assertEquals("My Agent", result.name)
    }

    @Test
    fun `존재하지 않는 Agent 조회 시 예외 발생`() {
        every { agentRepository.findById("not-exist") } returns null

        assertThrows<AgentNotFoundException> {
            service.getById("not-exist")
        }
    }
}
