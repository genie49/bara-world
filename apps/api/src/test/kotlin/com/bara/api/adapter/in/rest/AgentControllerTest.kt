package com.bara.api.adapter.`in`.rest

import com.bara.api.adapter.`in`.rest.a2a.A2AMessageDto
import com.bara.api.adapter.`in`.rest.a2a.A2APartDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskStatusDto
import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.command.StreamMessageUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.`in`.query.SubscribeTaskQuery
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.A2AErrorCodes
import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentNotRegisteredException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import java.time.Instant
import java.util.concurrent.CompletableFuture

@WebMvcTest(controllers = [AgentController::class])
@Import(ApiExceptionHandler::class, A2AExceptionHandler::class)
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
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
    lateinit var registryAgentUseCase: RegistryAgentUseCase

    @MockkBean
    lateinit var heartbeatAgentUseCase: HeartbeatAgentUseCase

    @MockkBean
    lateinit var listAgentsQuery: ListAgentsQuery

    @MockkBean
    lateinit var getAgentQuery: GetAgentQuery

    @MockkBean
    lateinit var getAgentCardQuery: GetAgentCardQuery

    @MockkBean
    lateinit var getTaskQuery: GetTaskQuery

    @MockkBean
    lateinit var sendMessageUseCase: SendMessageUseCase

    @MockkBean
    lateinit var streamMessageUseCase: StreamMessageUseCase

    @MockkBean
    lateinit var subscribeTaskQuery: SubscribeTaskQuery

    @MockkBean
    lateinit var taskPublisherPort: TaskPublisherPort

    private val agentCard = AgentCard(
        name = "Test Agent",
        description = "A test agent",
        version = "1.0.0",
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
                        "version": "1.0.0"
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
            content = """{"name":"dup","agentCard":{"name":"A","description":"d","version":"1"}}"""
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

    @Test
    fun `POST agents registry 성공 시 200`() {
        justRun { registryAgentUseCase.registry("p-1", "my-agent") }

        mockMvc.post("/agents/my-agent/registry") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `POST agents registry 미존재 Agent 시 404`() {
        every { registryAgentUseCase.registry("p-1", "unknown") } throws AgentNotFoundException()

        mockMvc.post("/agents/unknown/registry") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `POST agents registry 소유권 불일치 시 403`() {
        every { registryAgentUseCase.registry("p-1", "other-agent") } throws AgentOwnershipException()

        mockMvc.post("/agents/other-agent/registry") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST agents heartbeat 성공 시 200`() {
        justRun { heartbeatAgentUseCase.heartbeat("p-1", "my-agent") }

        mockMvc.post("/agents/my-agent/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `POST agents heartbeat 미등록 Agent 시 404`() {
        every { heartbeatAgentUseCase.heartbeat("p-1", "unknown") } throws AgentNotRegisteredException("unknown")

        mockMvc.post("/agents/unknown/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_registered") }
        }
    }

    @Test
    fun `POST agents heartbeat 소유권 불일치 시 403`() {
        every { heartbeatAgentUseCase.heartbeat("p-1", "other-agent") } throws AgentOwnershipException()

        mockMvc.post("/agents/other-agent/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST agents message send 성공 시 200 + A2ATaskDto`() {
        val dto = A2ATaskDto(
            id = "t-1",
            contextId = "c-1",
            status = A2ATaskStatusDto(
                state = "completed",
                message = A2AMessageDto(
                    messageId = "m-2", role = "agent",
                    parts = listOf(A2APartDto("text", "done")),
                ),
                timestamp = "2026-04-11T00:00:00Z",
            ),
        )
        every {
            sendMessageUseCase.sendBlocking(eq("user-1"), eq("my-agent"), any())
        } returns CompletableFuture.completedFuture(dto)

        val mvcResult = mockMvc.post("/agents/my-agent/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "jsonrpc": "2.0",
                    "id": "req-1",
                    "method": "message/send",
                    "params": {
                        "message": {
                            "messageId": "msg-1",
                            "parts": [{"text":"hello"}]
                        },
                        "contextId": "ctx-1"
                    }
                }
            """.trimIndent()
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.jsonrpc").value("2.0"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.id").value("req-1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.result.id").value("t-1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.result.status.state").value("completed"))
    }

    @Test
    fun `POST agents message send Agent 비활성 시 503`() {
        every { sendMessageUseCase.sendBlocking(any(), eq("dead"), any()) } throws AgentUnavailableException()

        mockMvc.post("/agents/dead/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","id":"req-1","method":"message/send","params":{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_UNAVAILABLE) }
            jsonPath("$.error.message") { value("Agent is not available") }
            jsonPath("$.result") { doesNotExist() }
        }
    }

    @Test
    fun `POST agents message send Agent 타임아웃 시 504`() {
        val failed = CompletableFuture<A2ATaskDto>()
        failed.completeExceptionally(AgentTimeoutException())
        every { sendMessageUseCase.sendBlocking(any(), eq("slow"), any()) } returns failed

        val mvcResult = mockMvc.post("/agents/slow/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","id":"req-1","method":"message/send","params":{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}}"""
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(MockMvcResultMatchers.status().isGatewayTimeout)
            .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value(A2AErrorCodes.AGENT_TIMEOUT))
            .andExpect(MockMvcResultMatchers.jsonPath("$.error.message").value("Agent did not respond within timeout"))
    }

    @Test
    fun `POST agents message send Kafka 실패 시 502`() {
        every { sendMessageUseCase.sendBlocking(any(), eq("my-agent"), any()) } throws
            KafkaPublishException("broker down")

        mockMvc.post("/agents/my-agent/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"jsonrpc":"2.0","id":"req-1","method":"message/send","params":{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}}"""
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error.code") { value(A2AErrorCodes.KAFKA_PUBLISH_FAILED) }
            jsonPath("$.error.message") { value("broker down") }
        }
    }

    @Test
    fun `sendMessage - returnImmediately true - sendAsync 호출 후 submitted DTO envelope 반환`() {
        val submittedDto = A2ATaskDto(
            id = "task-async-1",
            contextId = "ctx-1",
            status = A2ATaskStatusDto(
                state = "submitted",
                message = null,
                timestamp = Instant.parse("2026-04-11T00:00:00Z").toString(),
            ),
            artifacts = emptyList(),
        )
        every {
            sendMessageUseCase.sendAsync("user-1", "my-agent", any())
        } returns submittedDto

        val body = """
            {
                "jsonrpc": "2.0",
                "id": "req-async-1",
                "method": "message/send",
                "params": {
                    "message": {
                        "messageId": "m-async-1",
                        "parts": [{"text": "hi"}]
                    },
                    "configuration": { "returnImmediately": true }
                }
            }
        """.trimIndent()

        val mvcResult = mockMvc.post("/agents/my-agent/message:send") {
            contentType = MediaType.APPLICATION_JSON
            header("X-User-Id", "user-1")
            content = body
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.id").value("req-async-1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.result.id").value("task-async-1"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.result.status.state").value("submitted"))
    }

    @Test
    fun `getTask - 정상 조회 - 200 envelope result 반환`() {
        val dto = A2ATaskDto(
            id = "task-1",
            contextId = "ctx-1",
            status = A2ATaskStatusDto(
                state = "completed",
                message = null,
                timestamp = Instant.parse("2026-04-11T00:00:00Z").toString(),
            ),
            artifacts = emptyList(),
        )
        every { getTaskQuery.getTask("user-1", "task-1") } returns dto

        mockMvc.get("/agents/my-agent/tasks/task-1") {
            header("X-User-Id", "user-1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.result.id") { value("task-1") }
            jsonPath("$.result.status.state") { value("completed") }
            jsonPath("$.error") { doesNotExist() }
        }
    }

    @Test
    fun `getTask - TaskNotFoundException - 404 envelope error`() {
        every { getTaskQuery.getTask("user-1", "missing") } throws TaskNotFoundException("missing")

        mockMvc.get("/agents/my-agent/tasks/missing") {
            header("X-User-Id", "user-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_NOT_FOUND) }
            jsonPath("$.error.message") { value("Task not found: missing") }
        }
    }

    @Test
    fun `getTask - TaskAccessDeniedException - 403 envelope error`() {
        every { getTaskQuery.getTask("attacker", "task-1") } throws TaskAccessDeniedException("task-1")

        mockMvc.get("/agents/my-agent/tasks/task-1") {
            header("X-User-Id", "attacker")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_ACCESS_DENIED) }
            jsonPath("$.error.message") { value("Task access denied: task-1") }
        }
    }
}
