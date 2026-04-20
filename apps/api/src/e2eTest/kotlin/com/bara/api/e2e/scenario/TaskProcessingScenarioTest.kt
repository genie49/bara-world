package com.bara.api.e2e.scenario

import com.bara.api.e2e.support.E2eTestBase
import com.bara.api.e2e.support.FakeAgentKafka
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.util.UUID

/**
 * Phase 1 blocking sync end-to-end scenarios.
 *
 * 각 테스트는:
 *   1. 신규 agent를 HTTP로 등록
 *   2. /registry 호출해 Redis에 agentName → agentId 맵핑
 *   3. FakeAgentKafka에 agentId별 Behavior 주입
 *   4. POST /agents/{agentName}/message:send 실행 후 상태 검증
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskProcessingScenarioTest : E2eTestBase() {

    @LocalServerPort
    private var port: Int = 0

    private val http: RestTemplate by lazy {
        RestTemplate().apply {
            errorHandler = object : DefaultResponseErrorHandler() {
                override fun hasError(statusCode: org.springframework.http.HttpStatusCode) = false
            }
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private lateinit var fakeAgent: FakeAgentKafka

    @BeforeAll
    fun startFakeAgent() {
        fakeAgent = FakeAgentKafka().also { it.start() }
    }

    @AfterAll
    fun stopFakeAgent() {
        fakeAgent.stop()
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun registerAgent(providerId: String, agentName: String): String {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Provider-Id", providerId)
        }
        val body = """
            {
                "name": "$agentName",
                "agentCard": {
                    "name": "$agentName",
                    "description": "Phase1 e2e agent",
                    "version": "1.0.0",
                    "defaultInputModes": ["text/plain"],
                    "defaultOutputModes": ["text/plain"],
                    "capabilities": { "streaming": false, "pushNotifications": false },
                    "skills": []
                }
            }
        """.trimIndent()
        val response = http.exchange(
            url("/api/core/agents"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["id"] as String
    }

    private fun registry(providerId: String, agentName: String) {
        val headers = HttpHeaders().apply { set("X-Provider-Id", providerId) }
        val response = http.exchange(
            url("/api/core/agents/$agentName/registry"),
            HttpMethod.POST,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun sendMessage(
        userId: String,
        agentName: String,
        text: String,
        returnImmediately: Boolean = false,
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-User-Id", userId)
        }
        val config = if (returnImmediately) {
            """, "configuration": { "returnImmediately": true }"""
        } else {
            ""
        }
        val body = """
            {
                "jsonrpc": "2.0",
                "id": "req-${UUID.randomUUID()}",
                "method": "message/send",
                "params": {
                    "message": {
                        "messageId": "${UUID.randomUUID()}",
                        "parts": [{"text": "$text"}]
                    }$config
                }
            }
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        return http.exchange(
            url("/api/core/agents/$agentName/message:send"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as org.springframework.http.ResponseEntity<Map<*, *>>
    }

    private fun getTask(
        userId: String,
        agentName: String,
        taskId: String,
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        @Suppress("UNCHECKED_CAST")
        return http.exchange(
            url("/api/core/agents/$agentName/tasks/$taskId"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as org.springframework.http.ResponseEntity<Map<*, *>>
    }

    // ───────────────────────── scenarios ─────────────────────────

    @Test
    fun `1 - Happy path - FakeAgent가 completed 발행하면 200 + state=completed`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "happy-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("pong"))

        val response = sendMessage(userId = "user-1", agentName = agentName, text = "ping")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["jsonrpc"]).isEqualTo("2.0")
        assertThat(body["error"]).isNull()
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("completed")

        val artifacts = result["artifacts"] as List<*>
        assertThat(artifacts).hasSize(1)
        val artifact = artifacts[0] as Map<*, *>
        val parts = artifact["parts"] as List<*>
        val part = parts[0] as Map<*, *>
        assertThat(part["text"]).isEqualTo("pong")
    }

    @Test
    fun `2 - FakeAgent가 failed 발행하면 200 + state=failed`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "failing-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(
            agentId,
            FakeAgentKafka.Behavior.failed(errorCode = "agent_error", errorMessage = "intentional"),
        )

        val response = sendMessage(userId = "user-2", agentName = agentName, text = "boom")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("failed")
    }

    @Test
    fun `3 - Agent 침묵 - block-timeout-seconds 후 504 agent_timeout envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "silent-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.Silent)

        val started = System.currentTimeMillis()
        val response = sendMessage(userId = "user-3", agentName = agentName, text = "hello?")
        val elapsed = System.currentTimeMillis() - started

        assertThat(response.statusCode).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32063)
        assertThat(elapsed).isBetween(14_000L, 25_000L)
    }

    @Test
    fun `4 - registry 생략하면 503 agent_unavailable envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "unreg-${UUID.randomUUID()}"
        registerAgent(provider, agentName)

        val response = sendMessage(userId = "user-4", agentName = agentName, text = "hi")

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32062)
    }

    @Test
    fun `5 - Tasks collection에 expiredAt TTL index가 생성되어 있다`() {
        // happy path 1회 호출해서 문서가 insert되고 인덱스가 확실히 존재하도록
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "ttl-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("ok"))
        sendMessage(userId = "user-5", agentName = agentName, text = "first")

        val indexes = mongoTemplate.getCollection("tasks").listIndexes().toList()
        val expiredAtIndex = indexes.firstOrNull { doc ->
            val key = doc["key"] as? org.bson.Document ?: return@firstOrNull false
            key.containsKey("expiredAt")
        }
        assertThat(expiredAtIndex).`as`("expiredAt 필드에 TTL 인덱스가 있어야 함").isNotNull
        // MongoDB driver는 Integer로 반환하므로 Number로 받아 long 비교.
        val expireAfter = (expiredAtIndex!!["expireAfterSeconds"] as Number).toLong()
        assertThat(expireAfter).`as`("TTL expireAfterSeconds=0 이어야 함").isEqualTo(0L)
    }

    @Test
    fun `6 - returnImmediately true - 즉시 200 + state=submitted envelope 반환`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "async-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("ignored"))

        val started = System.currentTimeMillis()
        val response = sendMessage(
            userId = "user-6",
            agentName = agentName,
            text = "fire and forget",
            returnImmediately = true,
        )
        val elapsed = System.currentTimeMillis() - started

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("submitted")
        // Kafka publish ack (5s timeout) 내로 응답. Agent 처리 시간 대기 없음.
        assertThat(elapsed).isLessThan(5_000L)
    }

    @Test
    fun `7 - GET tasks taskId - Agent 완료 후 envelope result 반환`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "poll-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("polled"))

        val sendRes = sendMessage(userId = "user-7", agentName = agentName, text = "poll me")
        assertThat(sendRes.statusCode).isEqualTo(HttpStatus.OK)
        val sent = sendRes.body!!["result"] as Map<*, *>
        val taskId = sent["id"] as String

        val getRes = getTask(userId = "user-7", agentName = agentName, taskId = taskId)
        assertThat(getRes.statusCode).isEqualTo(HttpStatus.OK)
        val body = getRes.body!!
        assertThat(body["error"]).isNull()
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("completed")
    }

    @Test
    fun `8 - GET tasks taskId - 존재하지 않는 taskId → 404 task_not_found envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "missing-${UUID.randomUUID()}"
        registerAgent(provider, agentName)
        registry(provider, agentName)

        val response = getTask(
            userId = "user-8",
            agentName = agentName,
            taskId = "does-not-exist-${UUID.randomUUID()}",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32064)
    }

    @Test
    fun `9 - GET tasks taskId - 다른 userId 접근 → 403 task_access_denied envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "owned-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("secret"))

        val sendRes = sendMessage(userId = "owner-9", agentName = agentName, text = "private")
        val taskId = (sendRes.body!!["result"] as Map<*, *>)["id"] as String

        val response = getTask(userId = "attacker-9", agentName = agentName, taskId = taskId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32065)
    }
}
