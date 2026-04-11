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
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-User-Id", userId)
        }
        val body = """
            {
                "message": {
                    "messageId": "${UUID.randomUUID()}",
                    "role": "user",
                    "parts": [{"kind": "text", "text": "$text"}]
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
        val status = body["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("completed")

        val artifacts = body["artifacts"] as List<*>
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
        val status = body["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("failed")
        // 현재 A2ATaskMapper는 errorCode/Message를 DTO에 포함하지 않음(Phase 2 작업).
        // Mongo상의 task row가 FAILED로 저장됐는지는 Phase 2에서 확인.
    }

    @Test
    fun `3 - Agent 침묵 - block-timeout-seconds 후 504 agent_timeout`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "silent-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.Silent)

        val started = System.currentTimeMillis()
        val response = sendMessage(userId = "user-3", agentName = agentName, text = "hello?")
        val elapsed = System.currentTimeMillis() - started

        assertThat(response.statusCode).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        assertThat(response.body!!["error"]).isEqualTo("agent_timeout")
        // block-timeout-seconds=15 이므로 대략 그 근처 (14-25초 여유).
        assertThat(elapsed).isBetween(14_000L, 25_000L)
    }

    @Test
    fun `4 - registry 생략하면 503 agent_unavailable`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "unreg-${UUID.randomUUID()}"
        registerAgent(provider, agentName)
        // registry() 호출 생략 → Redis mapping 없음

        val response = sendMessage(userId = "user-4", agentName = agentName, text = "hi")

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body!!["error"]).isEqualTo("agent_unavailable")
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
}
