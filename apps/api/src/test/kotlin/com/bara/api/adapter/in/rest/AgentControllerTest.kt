package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(controllers = [AgentController::class])
@Import(ApiExceptionHandler::class)
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
    ]
)
class AgentControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var registerAgentUseCase: RegisterAgentUseCase

    @MockkBean
    lateinit var deleteAgentUseCase: DeleteAgentUseCase

    @MockkBean
    lateinit var listAgentsQuery: ListAgentsQuery

    @MockkBean
    lateinit var getAgentQuery: GetAgentQuery

    @MockkBean
    lateinit var getAgentCardQuery: GetAgentCardQuery

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
        defaultInputModes = listOf("text/plain"),
        defaultOutputModes = listOf("text/plain"),
        capabilities = AgentCard.AgentCapabilities(),
        skills = listOf(AgentCard.AgentSkill(id = "s1", name = "Skill 1", description = "A skill")),
    )

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private val agent = Agent(
        id = "a-1", name = "My Agent", providerId = "p-1",
        agentCard = agentCard, createdAt = now,
    )

    @Test
    fun `POST agents 성공 시 201과 Agent 정보 반환`() {
        every { registerAgentUseCase.register("p-1", any<RegisterAgentCommand>()) } returns agent

        mockMvc.post("/agents") {
            header("X-Provider-Id", "p-1")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "name": "My Agent",
                    "agentCard": {
                        "name": "Test Agent",
                        "description": "A test agent",
                        "version": "1.0.0",
                        "defaultInputModes": ["text/plain"],
                        "defaultOutputModes": ["text/plain"],
                        "capabilities": {"streaming": false, "pushNotifications": false},
                        "skills": [{"id": "s1", "name": "Skill 1", "description": "A skill"}]
                    }
                }
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("a-1") }
            jsonPath("$.name") { value("My Agent") }
            jsonPath("$.providerId") { value("p-1") }
            jsonPath("$.agentCard.name") { value("Test Agent") }
        }
    }

    @Test
    fun `POST agents 이름 중복 시 409 반환`() {
        every { registerAgentUseCase.register("p-1", any()) } throws AgentNameAlreadyExistsException()

        mockMvc.post("/agents") {
            header("X-Provider-Id", "p-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"dup","agentCard":{"name":"A","description":"d","version":"1","defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"capabilities":{},"skills":[]}}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("agent_name_already_exists") }
        }
    }

    @Test
    fun `GET agents 목록 반환`() {
        every { listAgentsQuery.listAll() } returns listOf(agent)

        mockMvc.get("/agents").andExpect {
            status { isOk() }
            jsonPath("$.agents.length()") { value(1) }
            jsonPath("$.agents[0].id") { value("a-1") }
            jsonPath("$.agents[0].name") { value("My Agent") }
        }
    }

    @Test
    fun `GET agents by id 성공`() {
        every { getAgentQuery.getById("a-1") } returns agent

        mockMvc.get("/agents/a-1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value("a-1") }
            jsonPath("$.agentCard.name") { value("Test Agent") }
        }
    }

    @Test
    fun `GET agents by id 미존재 시 404`() {
        every { getAgentQuery.getById("not-exist") } throws AgentNotFoundException()

        mockMvc.get("/agents/not-exist").andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_found") }
        }
    }

    @Test
    fun `GET agent card 성공`() {
        every { getAgentCardQuery.getCardById("a-1") } returns agentCard

        mockMvc.get("/agents/a-1/.well-known/agent.json").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Test Agent") }
            jsonPath("$.skills.length()") { value(1) }
        }
    }

    @Test
    fun `DELETE agents 성공 시 204`() {
        justRun { deleteAgentUseCase.delete("p-1", "a-1") }

        mockMvc.delete("/agents/a-1") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `DELETE 미존재 Agent 시 404`() {
        every { deleteAgentUseCase.delete("p-1", "not-exist") } throws AgentNotFoundException()

        mockMvc.delete("/agents/not-exist") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_found") }
        }
    }
}
