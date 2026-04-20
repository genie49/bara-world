package com.bara.api.e2e.scenario

import com.bara.api.e2e.support.E2eTestBase
import com.bara.api.e2e.support.FakeAgentKafka
import com.bara.api.e2e.support.SseTestClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 3 SSE end-to-end 시나리오.
 *
 * 1. `message:stream` 가 submitted → terminal (completed) 프레임을 Redis 스트림 ID 로 emit 하는지 검증
 * 2. `tasks/{id}:subscribe` 에 Last-Event-ID 를 주면 해당 ID 이후 이벤트만 replay 되는지 검증
 * 3. Redis Stream 이 사라진 뒤 `:subscribe` 호출 시 410 + JSON-RPC `-32067` envelope 를 반환하는지 검증
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class TaskStreamingScenarioTest : E2eTestBase() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val http: RestTemplate by lazy {
        RestTemplate().apply {
            errorHandler = object : DefaultResponseErrorHandler() {
                override fun hasError(statusCode: org.springframework.http.HttpStatusCode) = false
            }
        }
    }
    private val mapper: ObjectMapper = jacksonObjectMapper()

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
                    "description": "Phase3 SSE e2e agent",
                    "version": "1.0.0",
                    "defaultInputModes": ["text/plain"],
                    "defaultOutputModes": ["text/plain"],
                    "capabilities": { "streaming": true, "pushNotifications": false },
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

    private data class StreamEnvelope(val json: String, val requestId: String)

    private fun streamEnvelope(text: String): StreamEnvelope {
        val reqId = "req-${UUID.randomUUID()}"
        val msgId = UUID.randomUUID().toString()
        val json = """
            {"jsonrpc":"2.0","id":"$reqId","method":"message/stream",
             "params":{"message":{"messageId":"$msgId","parts":[{"text":"$text"}]}}}
        """.trimIndent()
        return StreamEnvelope(json, reqId)
    }

    /**
     * [SseTestClient] 가 terminal frame(또는 close) 에 도달할 때까지 프레임을 수집한다.
     * `state` 가 `{completed, failed, canceled, rejected}` 중 하나이거나, 더 이상 이벤트가 오지 않으면 중단.
     */
    private fun collectUntilTerminal(
        client: SseTestClient,
        perEventTimeoutMs: Long = 15_000,
        maxEvents: Int = 20,
    ): List<SseTestClient.Received> {
        val frames = mutableListOf<SseTestClient.Received>()
        val terminalStates = setOf("completed", "failed", "canceled", "rejected")
        while (frames.size < maxEvents) {
            val event = client.nextEvent(perEventTimeoutMs) ?: break
            frames += event
            val state = runCatching { mapper.readTree(event.data).path("result").path("status").path("state").asText() }
                .getOrNull()
            if (state in terminalStates) break
        }
        return frames
    }

    private fun idPattern() = Regex("""\d+-\d+""")

    // ───────────────────────── scenarios ─────────────────────────

    @Test
    fun `1 - message stream emits submitted then terminal completed frame with Redis entry ids`() {
        val provider = "e2e-sse-${UUID.randomUUID()}"
        val agentName = "stream-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("pong"))

        val envelope = streamEnvelope("ping")
        val client = SseTestClient(
            url = url("/api/core/agents/$agentName/message:stream"),
            headers = mapOf(
                "X-User-Id" to "user-sse-1",
                "Accept" to MediaType.TEXT_EVENT_STREAM_VALUE,
            ),
        )

        val frames = try {
            client.openPost(envelope.json, MediaType.APPLICATION_JSON_VALUE)
            collectUntilTerminal(client)
        } finally {
            client.close()
        }

        assertThat(frames).`as`("at least submitted + terminal frame expected").hasSizeGreaterThanOrEqualTo(2)

        val first = frames.first()
        val firstNode: JsonNode = mapper.readTree(first.data)
        assertThat(firstNode.path("jsonrpc").asText()).isEqualTo("2.0")
        assertThat(firstNode.path("id").asText()).isEqualTo(envelope.requestId)
        assertThat(firstNode.path("result").path("status").path("state").asText()).isEqualTo("submitted")
        assertThat(first.id).`as`("first frame id should match Redis entry format").matches(idPattern().pattern)

        val terminal = frames.last()
        val terminalNode = mapper.readTree(terminal.data)
        val terminalResult = terminalNode.path("result")
        assertThat(terminalNode.path("jsonrpc").asText()).isEqualTo("2.0")
        assertThat(terminalNode.path("id").asText()).isEqualTo(envelope.requestId)
        assertThat(terminalResult.path("status").path("state").asText()).isEqualTo("completed")
        assertThat(terminalResult.path("id").asText()).isNotBlank()
        val artifacts = terminalResult.path("artifacts")
        assertThat(artifacts.isArray).isTrue()
        assertThat(artifacts).hasSize(1)
        assertThat(artifacts[0].path("parts")[0].path("text").asText()).isEqualTo("pong")
        assertThat(terminal.id).`as`("terminal frame id should match Redis entry format").matches(idPattern().pattern)
    }

    @Test
    fun `2 - subscribe with Last-Event-ID replays only events after the given entry id`() {
        val provider = "e2e-sse-${UUID.randomUUID()}"
        val agentName = "resub-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("done"))

        // ── ① stream 시작 + 첫 프레임(id=submitted) 캡처 후 즉시 닫기
        val envelope = streamEnvelope("resume me")
        val firstClient = SseTestClient(
            url = url("/api/core/agents/$agentName/message:stream"),
            headers = mapOf(
                "X-User-Id" to "user-sse-2",
                "Accept" to MediaType.TEXT_EVENT_STREAM_VALUE,
            ),
        )
        val firstFrame: SseTestClient.Received
        val taskId: String
        try {
            firstClient.openPost(envelope.json, MediaType.APPLICATION_JSON_VALUE)
            firstFrame = firstClient.nextEvent(15_000)
                ?: error("Expected submitted frame from initial stream")
            val firstNode = mapper.readTree(firstFrame.data)
            assertThat(firstNode.path("result").path("status").path("state").asText()).isEqualTo("submitted")
            taskId = firstNode.path("result").path("id").asText()
            assertThat(taskId).isNotBlank()
            assertThat(firstFrame.id).matches(idPattern().pattern)
        } finally {
            firstClient.close()
        }

        // ── ② 재연결: 방금 캡처한 first frame id 를 Last-Event-ID 로 보내서 그 이후 이벤트만 replay
        val resubClient = SseTestClient(
            url = url("/api/core/agents/$agentName/tasks/$taskId:subscribe"),
            headers = mapOf(
                "X-User-Id" to "user-sse-2",
                "Accept" to MediaType.TEXT_EVENT_STREAM_VALUE,
                "Last-Event-ID" to (firstFrame.id ?: error("first frame had no id")),
            ),
        )
        val replay = try {
            resubClient.openGet()
            collectUntilTerminal(resubClient)
        } finally {
            resubClient.close()
        }

        // submitted 프레임이 replay 에 포함되면 안 된다. (XREAD id=X 는 strictly-after 동작)
        val states = replay.map {
            mapper.readTree(it.data).path("result").path("status").path("state").asText()
        }
        assertThat(states).`as`("replay 에 submitted 가 포함되면 안 됨").doesNotContain("submitted")

        // 최소 한 프레임은 있어야 하고, 마지막은 completed 여야 한다.
        assertThat(replay).isNotEmpty
        val terminal = replay.last()
        val terminalNode = mapper.readTree(terminal.data)
        assertThat(terminalNode.path("result").path("status").path("state").asText()).isEqualTo("completed")
        assertThat(terminalNode.path("result").path("id").asText()).isEqualTo(taskId)
    }

    @Test
    fun `3 - subscribe to expired stream returns 410 with stream_unsupported envelope`() {
        val provider = "e2e-sse-${UUID.randomUUID()}"
        val agentName = "expired-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("stale"))

        // 태스크를 블로킹으로 먼저 완료시켜서 Redis stream 을 생성해 둔다.
        val sendHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-User-Id", "user-sse-3")
        }
        val sendBody = """
            {
                "jsonrpc": "2.0",
                "id": "req-${UUID.randomUUID()}",
                "method": "message/send",
                "params": {
                    "message": {
                        "messageId": "${UUID.randomUUID()}",
                        "parts": [{"text": "expire me"}]
                    }
                }
            }
        """.trimIndent()
        val sendRes = http.exchange(
            url("/api/core/agents/$agentName/message:send"),
            HttpMethod.POST,
            HttpEntity(sendBody, sendHeaders),
            Map::class.java,
        )
        assertThat(sendRes.statusCode).isEqualTo(HttpStatus.OK)
        val taskId = ((sendRes.body!!["result"] as Map<*, *>)["id"]) as String

        // Redis stream 강제 삭제 → 만료 상태 재현.
        // (완료 직후 stream-grace-period-seconds=2 TTL 이 걸리므로 이미 자연 만료됐을 수도 있다.
        //  어느 쪽이든 subscribe 시점엔 key 가 없어야 한다.)
        val streamKey = "stream:task:$taskId"
        redisTemplate.delete(streamKey)
        assertThat(redisTemplate.hasKey(streamKey))
            .`as`("stream key 가 subscribe 시점에 존재하지 않아야 함")
            .isFalse()

        // SSE 가 아니라 평범한 GET 으로 만료 응답을 받는다. produces=text/event-stream 이므로 Accept 는 맞춰준다.
        val headers = HttpHeaders().apply {
            set("X-User-Id", "user-sse-3")
            set(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        }
        val response = http.exchange(
            url("/api/core/agents/$agentName/tasks/$taskId:subscribe"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.GONE)
        val body = response.body!!
        assertThat(body["jsonrpc"]).isEqualTo("2.0")
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32067)
    }
}
