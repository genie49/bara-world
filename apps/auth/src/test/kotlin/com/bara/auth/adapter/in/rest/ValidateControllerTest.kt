package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ValidateResult
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [ValidateController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class ValidateControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var validateTokenUseCase: ValidateTokenUseCase

    @MockkBean
    lateinit var jwtVerifier: JwtVerifier

    @Test
    fun `User JWT 검증 성공 시 200과 X-User-Id, X-User-Role, X-Request-Id 헤더 반환`() {
        every { validateTokenUseCase.validate("valid-jwt") } returns
            ValidateResult.UserResult(userId = "u-1", role = "USER")

        mockMvc.get("/validate") {
            header("Authorization", "Bearer valid-jwt")
        }.andExpect {
            status { isOk() }
            header { exists("X-User-Id") }
            header { string("X-User-Id", "u-1") }
            header { exists("X-User-Role") }
            header { string("X-User-Role", "USER") }
            header { exists("X-Request-Id") }
        }
    }

    @Test
    fun `API Key 검증 성공 시 200과 X-Provider-Id, X-Request-Id 헤더 반환`() {
        every { validateTokenUseCase.validate("bk_test_key") } returns
            ValidateResult.ProviderResult(providerId = "p-1")

        mockMvc.get("/validate") {
            header("Authorization", "Bearer bk_test_key")
        }.andExpect {
            status { isOk() }
            header { exists("X-Provider-Id") }
            header { string("X-Provider-Id", "p-1") }
            header { exists("X-Request-Id") }
        }
    }

    @Test
    fun `Authorization 헤더가 없으면 401 반환`() {
        mockMvc.get("/validate")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `유효하지 않은 토큰이면 401 반환`() {
        every { validateTokenUseCase.validate("bad-token") } throws
            InvalidTokenException("Invalid token")

        mockMvc.get("/validate") {
            header("Authorization", "Bearer bad-token")
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
