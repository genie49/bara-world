package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.`in`.query.GetProviderQuery
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.model.Provider
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(controllers = [ProviderController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class ProviderControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var registerUseCase: RegisterProviderUseCase

    @MockkBean
    lateinit var jwtVerifier: JwtVerifier

    @MockkBean
    lateinit var getProviderQuery: GetProviderQuery

    @Test
    fun `POST auth provider register 성공 시 201과 Provider 정보 반환`() {
        every { jwtVerifier.verify("test-jwt") } returns JwtClaims(userId = "u-1", email = "test@test.com", role = "USER")
        val now = Instant.parse("2024-01-01T00:00:00Z")
        every { registerUseCase.register("u-1", "my-provider") } returns Provider(
            id = "p-1",
            userId = "u-1",
            name = "my-provider",
            status = Provider.ProviderStatus.PENDING,
            createdAt = now,
        )

        mockMvc.post("/auth/provider/register") {
            header("Authorization", "Bearer test-jwt")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"my-provider"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value("p-1") }
            jsonPath("$.name") { value("my-provider") }
            jsonPath("$.status") { value("PENDING") }
        }
    }

    @Test
    fun `Provider 중복 등록 시 409 CONFLICT 반환`() {
        every { jwtVerifier.verify("test-jwt") } returns JwtClaims(userId = "u-1", email = "test@test.com", role = "USER")
        every { registerUseCase.register("u-1", "my-provider") } throws ProviderAlreadyExistsException()

        mockMvc.post("/auth/provider/register") {
            header("Authorization", "Bearer test-jwt")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"my-provider"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("provider_already_exists") }
        }
    }

    @Test
    fun `GET provider - 등록된 Provider가 있으면 200 반환`() {
        every { jwtVerifier.verify("test-jwt") } returns JwtClaims(userId = "u-1", email = "test@test.com", role = "USER")
        val now = Instant.parse("2024-01-01T00:00:00Z")
        every { getProviderQuery.getByUserId("u-1") } returns Provider(
            id = "p-1",
            userId = "u-1",
            name = "my-provider",
            status = Provider.ProviderStatus.ACTIVE,
            createdAt = now,
        )

        mockMvc.get("/auth/provider") {
            header("Authorization", "Bearer test-jwt")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value("p-1") }
            jsonPath("$.name") { value("my-provider") }
            jsonPath("$.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `GET provider - 미등록이면 404 반환`() {
        every { jwtVerifier.verify("test-jwt") } returns JwtClaims(userId = "u-1", email = "test@test.com", role = "USER")
        every { getProviderQuery.getByUserId("u-1") } returns null

        mockMvc.get("/auth/provider") {
            header("Authorization", "Bearer test-jwt")
        }.andExpect {
            status { isNotFound() }
        }
    }
}
