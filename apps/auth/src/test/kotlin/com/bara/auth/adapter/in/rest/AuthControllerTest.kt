package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
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

@WebMvcTest(controllers = [AuthController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var useCase: LoginWithGoogleUseCase

    @Test
    fun `GET auth google login 은 Google 로그인 URL로 302 리다이렉트`() {
        every { useCase.buildLoginUrl() } returns "https://accounts.google.com/oauth?state=xyz"

        mockMvc.get("/auth/google/login")
            .andExpect {
                status { isFound() }
                header { string("Location", "https://accounts.google.com/oauth?state=xyz") }
            }
    }

    @Test
    fun `GET auth google callback 성공 시 FE로 토큰과 함께 302`() {
        every { useCase.login("code-1", "state-1") } returns "jwt.token.xxx"

        mockMvc.get("/auth/google/callback") {
            param("code", "code-1")
            param("state", "state-1")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?token=jwt.token.xxx") }
        }
    }

    @Test
    fun `callback에서 state 불일치 시 invalid_state 에러로 302`() {
        every { useCase.login(any(), any()) } throws InvalidOAuthStateException()

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "bad")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=invalid_state") }
        }
    }

    @Test
    fun `callback에서 code 교환 실패 시 google_exchange_failed 에러로 302`() {
        every { useCase.login(any(), any()) } throws GoogleExchangeFailedException("x")

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=google_exchange_failed") }
        }
    }

    @Test
    fun `callback에서 id_token 검증 실패 시 invalid_id_token 에러로 302`() {
        every { useCase.login(any(), any()) } throws InvalidIdTokenException("x")

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=invalid_id_token") }
        }
    }
}
