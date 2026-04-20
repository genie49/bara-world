package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
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
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.test.assertEquals

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
class AgentControllerStreamTest {

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

    private val streamBody = """
        {
            "jsonrpc": "2.0",
            "id": "req-stream-1",
            "method": "message/stream",
            "params": {
                "message": {
                    "messageId": "msg-stream-1",
                    "parts": [{"text": "hello"}]
                },
                "contextId": "ctx-1"
            }
        }
    """.trimIndent()

    @Test
    fun `POST message stream - 성공 시 SSE async 시작과 text event-stream content-type`() {
        every {
            streamMessageUseCase.stream(eq("user-1"), eq("agent-a"), any(), any())
        } returns SseEmitter(0L)

        mockMvc.post("/agents/agent-a/message:stream") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = streamBody
        }.andExpect {
            request { asyncStarted() }
        }
    }

    @Test
    fun `GET tasks subscribe - 성공 시 SSE async 시작`() {
        every {
            subscribeTaskQuery.subscribe(eq("user-1"), eq("task-1"), isNull())
        } returns SseEmitter(0L)

        mockMvc.get("/agents/agent-a/tasks/task-1:subscribe") {
            header("X-User-Id", "user-1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }
    }

    @Test
    fun `GET tasks subscribe - Last-Event-ID 헤더가 UseCase 로 전달`() {
        val userIdSlot = slot<String>()
        val taskIdSlot = slot<String>()
        val lastEventIdSlot = slot<String?>()
        every {
            subscribeTaskQuery.subscribe(
                capture(userIdSlot),
                capture(taskIdSlot),
                captureNullable(lastEventIdSlot),
            )
        } returns SseEmitter(0L)

        mockMvc.get("/agents/agent-a/tasks/task-1:subscribe") {
            header("X-User-Id", "user-1")
            header("Last-Event-ID", "1714000000000-0")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }

        verify {
            subscribeTaskQuery.subscribe("user-1", "task-1", "1714000000000-0")
        }
        assertEquals("user-1", userIdSlot.captured)
        assertEquals("task-1", taskIdSlot.captured)
        assertEquals("1714000000000-0", lastEventIdSlot.captured)
    }

    @Test
    fun `GET tasks subscribe - StreamUnsupportedException - 410 envelope error`() {
        every {
            subscribeTaskQuery.subscribe("user-1", "expired-task", null)
        } throws StreamUnsupportedException()

        mockMvc.get("/agents/agent-a/tasks/expired-task:subscribe") {
            header("X-User-Id", "user-1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isGone() }
            jsonPath("$.error.code") { value(A2AErrorCodes.STREAM_UNSUPPORTED) }
            jsonPath("$.error.message") { value("Task stream is no longer available") }
        }
    }

    @Test
    fun `GET tasks subscribe - TaskNotFoundException - 404 envelope error`() {
        every {
            subscribeTaskQuery.subscribe("user-1", "missing", null)
        } throws TaskNotFoundException("missing")

        mockMvc.get("/agents/agent-a/tasks/missing:subscribe") {
            header("X-User-Id", "user-1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_NOT_FOUND) }
            jsonPath("$.error.message") { value("Task not found: missing") }
        }
    }

    @Test
    fun `GET tasks subscribe - TaskAccessDeniedException - 403 envelope error`() {
        every {
            subscribeTaskQuery.subscribe("attacker", "task-1", null)
        } throws TaskAccessDeniedException("task-1")

        mockMvc.get("/agents/agent-a/tasks/task-1:subscribe") {
            header("X-User-Id", "attacker")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_ACCESS_DENIED) }
            jsonPath("$.error.message") { value("Task access denied: task-1") }
        }
    }

    @Test
    fun `subscribe with Accept text-event-stream returns JSON error body on StreamUnsupportedException`() {
        every { subscribeTaskQuery.subscribe(any(), any(), any()) } throws StreamUnsupportedException("gone")

        mockMvc.get("/agents/agent-a/tasks/task-x:subscribe") {
            header("X-User-Id", "u1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isGone() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.error.code") { value(-32067) }
        }
    }
}
