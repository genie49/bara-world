package com.bara.auth.e2e.scenario

import com.bara.auth.e2e.support.E2eTestBase
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
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.lang.reflect.Field
import java.net.HttpURLConnection

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullFlowScenarioTest : E2eTestBase() {

    @LocalServerPort
    private var port: Int = 0

    /**
     * RestTemplate that does NOT follow redirects.
     * Used for all requests in this scenario chain because TestRestTemplate
     * (autowired from Spring Boot context) may not resolve relative URLs
     * reliably under @TestInstance(PER_CLASS). Using full URLs with
     * the injected port is more explicit and reliable.
     */
    private val http: RestTemplate by lazy {
        val factory = object : SimpleClientHttpRequestFactory() {
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                // Java's HttpURLConnection.setRequestMethod() forbids PATCH.
                // Use reflection to set the method field directly, then call super
                // with a dummy allowed method so setRequestMethod doesn't throw.
                if (httpMethod == "PATCH") {
                    forceMethod(connection, "PATCH")
                    // Call super with POST to satisfy setDoOutput/instanceFollowRedirects
                    // but with method already forced to PATCH via the field.
                    super.prepareConnection(connection, "POST")
                    // Re-force after super (super calls setRequestMethod which may reset)
                    forceMethod(connection, "PATCH")
                } else {
                    super.prepareConnection(connection, httpMethod)
                }
                connection.instanceFollowRedirects = false
            }

            private fun forceMethod(connection: HttpURLConnection, method: String) {
                try {
                    val field: Field = HttpURLConnection::class.java.getDeclaredField("method")
                    field.isAccessible = true
                    field.set(connection, method)
                } catch (_: Exception) { /* best-effort */ }
            }
        }
        RestTemplate(factory).apply {
            // Don't throw exceptions on 4xx/5xx — return the response instead
            errorHandler = object : DefaultResponseErrorHandler() {
                override fun hasError(statusCode: org.springframework.http.HttpStatusCode) = false
            }
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private lateinit var accessToken: String
    private lateinit var refreshToken: String
    private lateinit var userId: String
    private lateinit var providerId: String
    private lateinit var rawApiKey: String
    private lateinit var apiKeyId: String

    @BeforeEach
    override fun cleanDatabase() {
        // Don't clean - scenario tests share state across ordered tests
    }

    // ── 1. Google 로그인 ──────────────────────────────────────────

    @Test
    @Order(1)
    fun `1 - Google 로그인 후 JWT와 RefreshToken 획득`() {
        // Step 1: login 엔드포인트가 redirect URL을 반환하는지 확인
        val loginResponse = http.exchange(
            url("/api/auth/google/login"),
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(loginResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val redirectUrl = loginResponse.headers.location!!.toString()
        assertThat(redirectUrl).contains("fake-google")

        // Step 2: state 파라미터 추출
        val state = redirectUrl.substringAfter("state=")

        // Step 3: callback 호출 (FakeGoogleOAuthClient가 fake payload 반환)
        val callbackResponse = http.exchange(
            url("/api/auth/google/callback?code=fake-code&state=$state"),
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)

        // Step 4: redirect URL에서 token, refreshToken 추출
        val callbackRedirect = callbackResponse.headers.location!!.toString()
        assertThat(callbackRedirect).contains("token=")
        assertThat(callbackRedirect).contains("refreshToken=")

        val params = callbackRedirect.substringAfter("?").split("&")
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        accessToken = params["token"]!!
        refreshToken = params["refreshToken"]!!

        assertThat(accessToken).isNotBlank()
        assertThat(refreshToken).isNotBlank()
    }

    // ── 2. JWT validate ──────────────────────────────────────────

    @Test
    @Order(2)
    fun `2 - JWT로 validate 호출 시 X-User-Id 헤더 반환`() {
        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken)
        }
        val response = http.exchange(
            url("/api/auth/validate"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        userId = response.headers.getFirst("X-User-Id")!!
        assertThat(userId).isNotBlank()
        assertThat(response.headers.getFirst("X-User-Role")).isEqualTo("USER")
        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
    }

    // ── 3. Token refresh ─────────────────────────────────────────

    @Test
    @Order(3)
    fun `3 - RefreshToken으로 새 토큰 쌍 발급`() {
        val body = mapOf("refreshToken" to refreshToken)
        val response = http.postForEntity(url("/api/auth/refresh"), body, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val oldRefreshToken = refreshToken
        accessToken = response.body!!["accessToken"] as String
        refreshToken = response.body!!["refreshToken"] as String
        assertThat(accessToken).isNotBlank()
        assertThat(refreshToken).isNotEqualTo(oldRefreshToken)
    }

    // ── 4. 이전 refresh token 재사용 거부 ────────────────────────

    @Test
    @Order(4)
    fun `4 - 이전 RefreshToken 재사용 시 401`() {
        // Grace period (e2e에서는 1초)가 만료된 후 이전 토큰 재사용 → reuse detected
        // 먼저 한번 더 refresh해서 이전 JTI를 grace에 저장
        val body1 = mapOf("refreshToken" to refreshToken)
        val response1 = http.postForEntity(url("/api/auth/refresh"), body1, Map::class.java)
        assertThat(response1.statusCode).isEqualTo(HttpStatus.OK)
        val staleRefreshToken = refreshToken
        accessToken = response1.body!!["accessToken"] as String
        refreshToken = response1.body!!["refreshToken"] as String

        // grace-period-seconds=1 이므로 1.2초 대기 후 이전 토큰은 grace 밖
        Thread.sleep(1200)

        // grace 만료된 이전 토큰으로 재사용 시도 → reuse detected
        val body2 = mapOf("refreshToken" to staleRefreshToken)
        val response2 = http.postForEntity(url("/api/auth/refresh"), body2, Map::class.java)
        assertThat(response2.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── 5. Provider 등록 ─────────────────────────────────────────

    @Test
    @Order(5)
    fun `5 - Provider 등록`() {
        // 4번 테스트에서 family가 무효화되었으므로 다시 로그인
        reLogin()

        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "E2E Provider")
        val response = http.exchange(
            url("/api/auth/provider/register"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        providerId = response.body!!["id"] as String
        assertThat(response.body!!["name"]).isEqualTo("E2E Provider")
        assertThat(response.body!!["status"]).isEqualTo("PENDING")
    }

    // ── 6. Provider 조회 ─────────────────────────────────────────

    @Test
    @Order(6)
    fun `6 - Provider 조회 시 PENDING 상태`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = http.exchange(
            url("/api/auth/provider"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("PENDING")
    }

    // ── 7. API Key 발급 ──────────────────────────────────────────

    @Test
    @Order(7)
    fun `7 - Provider ACTIVE 전환 후 API Key 발급`() {
        // DB에서 직접 Provider status를 ACTIVE로 변경
        val collection = mongoTemplate.db.getCollection("providers")
        collection.updateOne(
            org.bson.Document("_id", providerId),
            org.bson.Document("\$set", org.bson.Document("status", "ACTIVE")),
        )

        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "E2E Key")
        val response = http.exchange(
            url("/api/auth/provider/api-key"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        rawApiKey = response.body!!["apiKey"] as String
        apiKeyId = response.body!!["id"] as String
        assertThat(rawApiKey).startsWith("bk_")
    }

    // ── 8. API Key validate ──────────────────────────────────────

    @Test
    @Order(8)
    fun `8 - API Key로 validate 호출 시 X-Provider-Id 반환`() {
        val headers = HttpHeaders().apply { setBearerAuth(rawApiKey) }
        val response = http.exchange(
            url("/api/auth/validate"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("X-Provider-Id")).isEqualTo(providerId)
        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
    }

    // ── 9. API Key 목록 조회 ─────────────────────────────────────

    @Test
    @Order(9)
    fun `9 - API Key 목록 조회 시 1개 반환`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = http.exchange(
            url("/api/auth/provider/api-key"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val keys = response.body!!["keys"] as List<*>
        assertThat(keys).hasSize(1)
    }

    // ── 10. API Key 이름 수정 ────────────────────────────────────

    @Test
    @Order(10)
    fun `10 - API Key 이름 수정`() {
        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "Renamed Key")
        val response = http.exchange(
            url("/api/auth/provider/api-key/$apiKeyId"),
            HttpMethod.PATCH,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["name"]).isEqualTo("Renamed Key")
    }

    // ── 11. API Key 삭제 ─────────────────────────────────────────

    @Test
    @Order(11)
    fun `11 - API Key 삭제`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = http.exchange(
            url("/api/auth/provider/api-key/$apiKeyId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── 12. 삭제된 API Key validate 실패 ─────────────────────────

    @Test
    @Order(12)
    fun `12 - 삭제된 API Key로 validate 시 401`() {
        val headers = HttpHeaders().apply { setBearerAuth(rawApiKey) }
        val response = http.exchange(
            url("/api/auth/validate"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── Helper ───────────────────────────────────────────────────

    private fun reLogin() {
        val loginResp = http.exchange(
            url("/api/auth/google/login"),
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        val state = loginResp.headers.location!!.toString().substringAfter("state=")
        val callbackResp = http.exchange(
            url("/api/auth/google/callback?code=fake-code&state=$state"),
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        val params = callbackResp.headers.location!!.toString().substringAfter("?").split("&")
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        accessToken = params["token"]!!
        refreshToken = params["refreshToken"]!!
    }
}
