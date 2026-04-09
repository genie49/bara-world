package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class RegisterAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val service = RegisterAgentService(agentRepository)

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
    )

    @Test
    fun `신규 Agent를 등록하면 저장된다`() {
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByName("My Agent") } returns null
        every { agentRepository.save(any()) } answers { firstArg() }

        val result = service.register("p-1", command)

        assertEquals("My Agent", result.name)
        assertEquals("p-1", result.providerId)
        assertEquals(agentCard, result.agentCard)
        verify { agentRepository.save(any()) }
    }

    @Test
    fun `같은 이름의 Agent가 있으면 예외 발생`() {
        val existing = Agent.create(name = "My Agent", providerId = "p-1", agentCard = agentCard)
        val command = RegisterAgentCommand(name = "My Agent", agentCard = agentCard)
        every { agentRepository.findByName("My Agent") } returns existing

        assertThrows<AgentNameAlreadyExistsException> {
            service.register("p-1", command)
        }
    }
}
