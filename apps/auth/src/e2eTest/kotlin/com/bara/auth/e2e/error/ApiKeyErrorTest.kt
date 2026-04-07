package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class ApiKeyErrorTest : E2eTestBase() {

    private val userId = "apikey-error-test-user"
    private lateinit var headers: HttpHeaders

    @BeforeEach
    fun setUpProvider() {
        headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }

        // Provider 등록
        rest.exchange(
            "/provider/register",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "ApiKey Error Provider"), headers),
            Map::class.java,
        )

        // DB에서 ACTIVE로 변경
        val providerDoc = mongoTemplate.db.getCollection("providers")
            .find(org.bson.Document("userId", userId)).first()!!
        mongoTemplate.db.getCollection("providers").updateOne(
            org.bson.Document("_id", providerDoc["_id"]),
            org.bson.Document("\$set", org.bson.Document("status", "ACTIVE")),
        )
    }

    @Test
    fun `API Key 5개 초과 발급 시 409`() {
        // 5개 발급
        repeat(5) { i ->
            val response = rest.exchange(
                "/provider/api-key",
                HttpMethod.POST,
                HttpEntity(mapOf("name" to "Key-$i"), headers),
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        }

        // 6번째 발급 시도
        val response = rest.exchange(
            "/provider/api-key",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Key-6"), headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["error"]).isEqualTo("api_key_limit_exceeded")
    }

    @Test
    fun `존재하지 않는 keyId 수정 시 404`() {
        val response = rest.exchange(
            "/provider/api-key/nonexistent-key-id",
            HttpMethod.PATCH,
            HttpEntity(mapOf("name" to "New Name"), headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("api_key_not_found")
    }

    @Test
    fun `존재하지 않는 keyId 삭제 시 404`() {
        val response = rest.exchange(
            "/provider/api-key/nonexistent-key-id",
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("api_key_not_found")
    }
}
