package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RefreshTokenUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.TokenPair
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
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [RefreshController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/api/auth/google/callback",
    ]
)
class RefreshControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var useCase: RefreshTokenUseCase

    @Test
    fun `유효한 Refresh Token으로 새 토큰 쌍이 반환된다`() {
        every { useCase.refresh("valid-refresh") } returns TokenPair("new-access", "new-refresh")

        mockMvc.post("/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"valid-refresh"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("new-access") }
            jsonPath("$.refreshToken") { value("new-refresh") }
            jsonPath("$.expiresIn") { value(3600) }
        }
    }

    @Test
    fun `잘못된 Refresh Token은 401을 반환한다`() {
        every { useCase.refresh("invalid") } throws InvalidTokenException("Invalid token")

        mockMvc.post("/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"invalid"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
