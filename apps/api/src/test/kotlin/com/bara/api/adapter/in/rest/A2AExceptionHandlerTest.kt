package com.bara.api.adapter.`in`.rest

import com.bara.api.domain.exception.A2AErrorCodes
import com.bara.api.domain.exception.A2AException
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Controller
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
@RequestMapping("/__test/a2a")
class A2AExceptionTestController {
    @GetMapping("/throw/{code}")
    @ResponseBody
    fun throwByCode(@PathVariable code: Int): Nothing = when (code) {
        A2AErrorCodes.AGENT_UNAVAILABLE -> throw AgentUnavailableException()
        A2AErrorCodes.AGENT_TIMEOUT -> throw AgentTimeoutException("simulated 30s timeout")
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> throw KafkaPublishException("broker down")
        A2AErrorCodes.TASK_NOT_FOUND -> throw TaskNotFoundException("task-missing")
        A2AErrorCodes.TASK_ACCESS_DENIED -> throw TaskAccessDeniedException("task-other")
        A2AErrorCodes.STREAM_UNSUPPORTED -> throw StreamUnsupportedException()
        else -> throw A2AException(code = code, message = "unknown")
    }
}

@WebMvcTest(controllers = [A2AExceptionTestController::class])
@Import(A2AExceptionHandler::class)
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    ],
)
class A2AExceptionHandlerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `AgentUnavailableException → 503 + code=-32062`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.AGENT_UNAVAILABLE}").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_UNAVAILABLE) }
            jsonPath("$.error.message") { value("Agent is not available") }
            jsonPath("$.result") { doesNotExist() }
            jsonPath("$.id") { doesNotExist() }
        }
    }

    @Test
    fun `AgentTimeoutException → 504 + code=-32063`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.AGENT_TIMEOUT}").andExpect {
            status { isGatewayTimeout() }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_TIMEOUT) }
            jsonPath("$.error.message") { value("simulated 30s timeout") }
        }
    }

    @Test
    fun `KafkaPublishException → 502 + code=-32001`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.KAFKA_PUBLISH_FAILED}").andExpect {
            status { isBadGateway() }
            jsonPath("$.error.code") { value(A2AErrorCodes.KAFKA_PUBLISH_FAILED) }
            jsonPath("$.error.message") { value("broker down") }
        }
    }

    @Test
    fun `TaskNotFoundException → 404 + code=-32064`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.TASK_NOT_FOUND}").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_NOT_FOUND) }
            jsonPath("$.error.message") { value("Task not found: task-missing") }
        }
    }

    @Test
    fun `TaskAccessDeniedException → 403 + code=-32065`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.TASK_ACCESS_DENIED}").andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_ACCESS_DENIED) }
            jsonPath("$.error.message") { value("Task access denied: task-other") }
        }
    }

    @Test
    fun `StreamUnsupportedException → 410 + code=-32067`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.STREAM_UNSUPPORTED}").andExpect {
            status { isGone() }
            jsonPath("$.error.code") { value(A2AErrorCodes.STREAM_UNSUPPORTED) }
            jsonPath("$.error.message") { value("Task stream is no longer available") }
        }
    }
}
