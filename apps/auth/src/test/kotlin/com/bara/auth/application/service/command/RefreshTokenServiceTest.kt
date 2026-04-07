package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class RefreshTokenServiceTest {

    private val refreshTokenIssuer = mockk<RefreshTokenIssuer>()
    private val refreshTokenStore = mockk<RefreshTokenStore>(relaxed = true)
    private val jwtIssuer = mockk<JwtIssuer>()
    private val userRepository = mockk<UserRepository>()

    private val service = RefreshTokenService(
        refreshTokenIssuer, refreshTokenStore, jwtIssuer, userRepository,
    )

    private val user = User(
        id = "user-1", googleId = "g-1", email = "test@test.com",
        name = "Test", role = User.Role.USER, createdAt = Instant.now(),
    )

    @Test
    fun `유효한 Refresh Token으로 새 Access와 Refresh가 발급된다`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("old-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "jti-1", family = "fam-1")
        every { userRepository.findById("user-1") } returns user
        every { jwtIssuer.issue(user) } returns "new-access"
        every { refreshTokenIssuer.issue("user-1", "fam-1") } returns "new-refresh"
        every { refreshTokenIssuer.verify("new-refresh") } returns RefreshTokenClaims(userId = "user-1", jti = "jti-2", family = "fam-1")

        val result = service.refresh("old-refresh")

        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
        verify { refreshTokenStore.saveGrace("jti-1") }
        verify { refreshTokenStore.save("user-1", "jti-2", "fam-1") }
    }

    @Test
    fun `JTI 불일치 시 재사용 감지로 family 전체 무효화`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "stolen-jti", family = "fam-1")
        every { refreshTokenIssuer.verify("stolen-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "current-jti", family = "fam-1")
        every { refreshTokenStore.isGraceValid("stolen-jti") } returns false

        assertThrows<InvalidTokenException> { service.refresh("stolen-refresh") }
        verify { refreshTokenStore.delete("user-1") }
    }

    @Test
    fun `JTI 불일치이지만 Grace Period 내면 허용된다`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "old-jti", family = "fam-1")
        every { refreshTokenIssuer.verify("old-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "new-jti", family = "fam-1")
        every { refreshTokenStore.isGraceValid("old-jti") } returns true
        every { userRepository.findById("user-1") } returns user
        every { jwtIssuer.issue(user) } returns "new-access"
        every { refreshTokenIssuer.issue("user-1", "fam-1") } returns "new-refresh"
        every { refreshTokenIssuer.verify("new-refresh") } returns RefreshTokenClaims(userId = "user-1", jti = "jti-3", family = "fam-1")

        val result = service.refresh("old-refresh")

        assertEquals("new-access", result.accessToken)
    }

    @Test
    fun `Redis에 저장된 토큰이 없으면 InvalidTokenException`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns null

        assertThrows<InvalidTokenException> { service.refresh("refresh") }
    }

    @Test
    fun `User가 존재하지 않으면 InvalidTokenException`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "jti-1", family = "fam-1")
        every { userRepository.findById("user-1") } returns null

        assertThrows<InvalidTokenException> { service.refresh("refresh") }
    }
}
