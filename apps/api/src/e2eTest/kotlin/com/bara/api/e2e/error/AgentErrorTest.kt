package com.bara.api.e2e.error

import com.bara.api.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.util.UUID

class AgentErrorTest : E2eTestBase() {

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

    private fun registerAgent(providerId: String, name: String): Map<*, *> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Provider-Id", providerId)
        }
        val body = """
            {
                "name": "$name",
                "agentCard": {
                    "name": "$name",
                    "description": "테스트 Agent",
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
        return response.body!!
    }

    @Test
    fun `존재하지 않는 Agent 조회 시 404`() {
        val response = http.exchange(
            url("/api/core/agents/${UUID.randomUUID()}"),
            HttpMethod.GET,
            null,
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("agent_not_found")
    }

    @Test
    fun `존재하지 않는 Agent 삭제 시 404`() {
        val headers = HttpHeaders().apply {
            set("X-Provider-Id", "some-provider")
        }
        val response = http.exchange(
            url("/api/core/agents/${UUID.randomUUID()}"),
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Void::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `동일 provider + 동일 이름 중복 등록 시 409`() {
        val providerId = "dup-provider"
        registerAgent(providerId, "duplicate-agent")

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Provider-Id", providerId)
        }
        val body = """
            {
                "name": "duplicate-agent",
                "agentCard": {
                    "name": "duplicate-agent",
                    "description": "중복 Agent",
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

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["error"]).isEqualTo("agent_name_already_exists")
    }

    @Test
    fun `다른 provider가 삭제 시도 시 404`() {
        val agent = registerAgent("owner-provider", "owned-agent")
        val agentId = agent["id"] as String

        val headers = HttpHeaders().apply {
            set("X-Provider-Id", "other-provider")
        }
        val response = http.exchange(
            url("/api/core/agents/$agentId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Void::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
