package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class ProviderErrorTest : E2eTestBase() {

    @Test
    fun `Provider 중복 등록 시 409`() {
        val userId = "duplicate-test-user"
        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "Provider 1")

        // 첫 번째 등록: 성공
        val response1 = rest.exchange(
            "/provider/register",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response1.statusCode).isEqualTo(HttpStatus.CREATED)

        // 두 번째 등록: 409
        val response2 = rest.exchange(
            "/provider/register",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response2.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response2.body!!["error"]).isEqualTo("provider_already_exists")
    }

    @Test
    fun `미등록 Provider 조회 시 404`() {
        val headers = HttpHeaders().apply { set("X-User-Id", "nonexistent-user") }
        val response = rest.exchange(
            "/provider",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `PENDING Provider에서 API Key 발급 시 403`() {
        val userId = "pending-test-user"
        val registerHeaders = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }

        // Provider 등록 (PENDING 상태)
        rest.exchange(
            "/provider/register",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Pending Provider"), registerHeaders),
            Map::class.java,
        )

        // PENDING 상태에서 API Key 발급 시도
        val response = rest.exchange(
            "/provider/api-key",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Key"), registerHeaders),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["error"]).isEqualTo("provider_not_active")
    }
}
