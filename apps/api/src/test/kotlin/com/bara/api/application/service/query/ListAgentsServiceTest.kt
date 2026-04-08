package com.bara.api.application.service.query

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListAgentsServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = ListAgentsService(agentRepository)

    @Test
    fun `전체 Agent 목록을 반환한다`() {
        val card = AgentCard(
            name = "A", description = "d", version = "1.0.0",
            defaultInputModes = listOf("text/plain"), defaultOutputModes = listOf("text/plain"),
            capabilities = AgentCard.AgentCapabilities(), skills = emptyList(),
        )
        val agents = listOf(
            Agent.create(name = "Agent 1", providerId = "p-1", agentCard = card),
            Agent.create(name = "Agent 2", providerId = "p-2", agentCard = card),
        )
        every { agentRepository.findAll() } returns agents

        val result = service.listAll()

        assertEquals(2, result.size)
    }

    @Test
    fun `Agent가 없으면 빈 목록 반환`() {
        every { agentRepository.findAll() } returns emptyList()

        val result = service.listAll()

        assertEquals(0, result.size)
    }
}
