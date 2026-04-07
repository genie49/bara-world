package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.DeleteApiKeyUseCase
import com.bara.auth.application.port.`in`.command.IssuedApiKey
import com.bara.auth.application.port.`in`.command.IssueApiKeyUseCase
import com.bara.auth.application.port.`in`.command.UpdateApiKeyNameUseCase
import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(controllers = [ApiKeyController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class ApiKeyControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var issueUseCase: IssueApiKeyUseCase

    @MockkBean
    lateinit var listQuery: ListApiKeysQuery

    @MockkBean
    lateinit var updateUseCase: UpdateApiKeyNameUseCase

    @MockkBean
    lateinit var deleteUseCase: DeleteApiKeyUseCase

    @MockkBean
    lateinit var jwtVerifier: JwtVerifier

    private val now = Instant.parse("2024-01-01T00:00:00Z")

    private fun stubJwt() {
        every { jwtVerifier.verify("test-jwt") } returns JwtClaims(userId = "u-1", email = "test@test.com", role = "USER")
    }

    private fun apiKey(id: String = "k-1", name: String = "my-key") = ApiKey(
        id = id,
        providerId = "p-1",
        name = name,
        keyHash = "hash",
        keyPrefix = "bara_",
        createdAt = now,
    )

    @Test
    fun `POST auth provider api-key 성공 시 201과 발급된 키 정보 반환`() {
        stubJwt()
        every { issueUseCase.issue("u-1", "my-key") } returns IssuedApiKey(
            apiKey = apiKey(),
            rawKey = "bara_rawsecretkey",
        )

        mockMvc.post("/auth/provider/api-key") {
            header("Authorization", "Bearer test-jwt")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"my-key"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("k-1") }
            jsonPath("$.name") { value("my-key") }
            jsonPath("$.apiKey") { value("bara_rawsecretkey") }
            jsonPath("$.prefix") { value("bara_") }
        }
    }

    @Test
    fun `GET auth provider api-key 목록 조회 성공 시 200과 키 목록 반환`() {
        stubJwt()
        every { listQuery.listByUserId("u-1") } returns listOf(
            apiKey("k-1", "key-one"),
            apiKey("k-2", "key-two"),
        )

        mockMvc.get("/auth/provider/api-key") {
            header("Authorization", "Bearer test-jwt")
        }.andExpect {
            status { isOk() }
            jsonPath("$.keys.length()") { value(2) }
            jsonPath("$.keys[0].id") { value("k-1") }
            jsonPath("$.keys[1].id") { value("k-2") }
        }
    }

    @Test
    fun `PATCH auth provider api-key keyId 이름 수정 성공 시 200과 수정된 키 반환`() {
        stubJwt()
        every { updateUseCase.update("u-1", "k-1", "new-name") } returns apiKey(name = "new-name")

        mockMvc.patch("/auth/provider/api-key/k-1") {
            header("Authorization", "Bearer test-jwt")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"new-name"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value("k-1") }
            jsonPath("$.name") { value("new-name") }
        }
    }

    @Test
    fun `DELETE auth provider api-key keyId 삭제 성공 시 204 반환`() {
        stubJwt()
        every { deleteUseCase.delete("u-1", "k-1") } just runs

        mockMvc.delete("/auth/provider/api-key/k-1") {
            header("Authorization", "Bearer test-jwt")
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `Provider 없음 시 404 반환`() {
        stubJwt()
        every { issueUseCase.issue("u-1", "my-key") } throws ProviderNotFoundException()

        mockMvc.post("/auth/provider/api-key") {
            header("Authorization", "Bearer test-jwt")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"my-key"}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("provider_not_found") }
        }
    }
}
