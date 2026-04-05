package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.bara.auth.domain.model.User
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class LoginWithGoogleServiceTest {

    private val googleClient = mockk<GoogleOAuthClient>()
    private val userRepo = mockk<UserRepository>()
    private val stateStore = mockk<OAuthStateStore>()
    private val jwtIssuer = mockk<JwtIssuer>()

    private val service = LoginWithGoogleService(googleClient, userRepo, stateStore, jwtIssuer)

    private val payload = GoogleIdTokenPayload(
        googleId = "google-123",
        email = "test@example.com",
        name = "Test User",
    )

    private val existingUser = User(
        id = "user-1",
        googleId = "google-123",
        email = "test@example.com",
        name = "Test User",
        role = User.Role.USER,
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

    @Test
    fun `기존 사용자는 저장 없이 JWT 발급`() {
        every { stateStore.consume("state-ok") } just Runs
        every { googleClient.exchangeCodeForIdToken("code-ok") } returns payload
        every { userRepo.findByGoogleId("google-123") } returns existingUser
        every { jwtIssuer.issue(existingUser) } returns "jwt.token.existing"

        val token = service.login(code = "code-ok", state = "state-ok")

        assertEquals("jwt.token.existing", token)
        verify(exactly = 0) { userRepo.save(any()) }
    }

    @Test
    fun `신규 사용자는 저장 후 JWT 발급`() {
        val savedSlot = slot<User>()
        every { stateStore.consume("state-ok") } just Runs
        every { googleClient.exchangeCodeForIdToken("code-ok") } returns payload
        every { userRepo.findByGoogleId("google-123") } returns null
        every { userRepo.save(capture(savedSlot)) } answers { savedSlot.captured }
        every { jwtIssuer.issue(any()) } returns "jwt.token.new"

        val token = service.login(code = "code-ok", state = "state-ok")

        assertEquals("jwt.token.new", token)
        assertEquals("google-123", savedSlot.captured.googleId)
        assertEquals("test@example.com", savedSlot.captured.email)
        assertEquals(User.Role.USER, savedSlot.captured.role)
        verify(exactly = 1) { userRepo.save(any()) }
    }

    @Test
    fun `state 검증 실패 시 예외가 전파된다`() {
        every { stateStore.consume("bad") } throws InvalidOAuthStateException()

        assertThrows(InvalidOAuthStateException::class.java) {
            service.login(code = "code", state = "bad")
        }

        verify(exactly = 0) { googleClient.exchangeCodeForIdToken(any()) }
    }

    @Test
    fun `buildLoginUrl은 state를 발급해 Google URL을 조립한다`() {
        every { stateStore.issue() } returns "generated-state"
        every { googleClient.buildAuthorizationUrl("generated-state") } returns "https://google/auth?state=generated-state"

        val url = service.buildLoginUrl()

        assertEquals("https://google/auth?state=generated-state", url)
    }
}
