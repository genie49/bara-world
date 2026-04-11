package com.bara.api.e2e.scenario

import com.bara.api.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentFlowScenarioTest : E2eTestBase() {

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

    private val providerId = "e2e-provider-001"
    private lateinit var agentId: String

    @BeforeEach
    override fun cleanDatabase() {
        // Don't clean - scenario tests share state across ordered tests
    }

    @Test
    @Order(1)
    fun `1 - Agent 등록`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Provider-Id", providerId)
        }
        val body = """
            {
                "name": "e2e-test-agent",
                "agentCard": {
                    "name": "e2e-test-agent",
                    "description": "E2E 테스트용 Agent",
                    "version": "1.0.0"
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
        val responseBody = response.body!!
        agentId = responseBody["id"] as String
        assertThat(agentId).isNotBlank()
        assertThat(responseBody["name"]).isEqualTo("e2e-test-agent")
        assertThat(responseBody["providerId"]).isEqualTo(providerId)
        assertThat(responseBody["agentCard"]).isNotNull()
    }

    @Test
    @Order(2)
    fun `2 - Agent 목록 조회`() {
        val response = http.exchange(
            url("/api/core/agents"),
            HttpMethod.GET,
            null,
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val agents = response.body!!["agents"] as List<*>
        assertThat(agents).hasSize(1)
        val agent = agents[0] as Map<*, *>
        assertThat(agent["name"]).isEqualTo("e2e-test-agent")
        assertThat(agent.containsKey("agentCard")).isFalse()
    }

    @Test
    @Order(3)
    fun `3 - Agent 상세 조회`() {
        val response = http.exchange(
            url("/api/core/agents/$agentId"),
            HttpMethod.GET,
            null,
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val responseBody = response.body!!
        assertThat(responseBody["id"]).isEqualTo(agentId)
        assertThat(responseBody["name"]).isEqualTo("e2e-test-agent")
        val agentCard = responseBody["agentCard"] as Map<*, *>
        assertThat(agentCard["name"]).isEqualTo("e2e-test-agent")
        assertThat(agentCard["description"]).isEqualTo("E2E 테스트용 Agent")
        assertThat(agentCard["version"]).isEqualTo("1.0.0")
    }

    @Test
    @Order(4)
    fun `4 - Agent Card 조회`() {
        val response = http.exchange(
            url("/api/core/agents/$agentId/.well-known/agent.json"),
            HttpMethod.GET,
            null,
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val card = response.body!!
        assertThat(card["name"]).isEqualTo("e2e-test-agent")
        assertThat(card["description"]).isEqualTo("E2E 테스트용 Agent")
        assertThat(card["version"]).isEqualTo("1.0.0")
    }

    @Test
    @Order(5)
    fun `5 - Agent 삭제`() {
        val headers = HttpHeaders().apply {
            set("X-Provider-Id", providerId)
        }
        val response = http.exchange(
            url("/api/core/agents/$agentId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Void::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    @Order(6)
    fun `6 - 삭제된 Agent 조회 시 404`() {
        val response = http.exchange(
            url("/api/core/agents/$agentId"),
            HttpMethod.GET,
            null,
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("agent_not_found")
    }
}
