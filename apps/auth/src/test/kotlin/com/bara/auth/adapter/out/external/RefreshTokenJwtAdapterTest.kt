package com.bara.auth.adapter.out.external

import com.bara.auth.config.JwtProperties
import com.bara.auth.config.RefreshTokenProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

class RefreshTokenJwtAdapterTest {

    private lateinit var adapter: RefreshTokenJwtAdapter
    private lateinit var jwtProps: JwtProperties

    private val refreshProps = RefreshTokenProperties(
        audience = "bara-refresh",
        expirySeconds = 604800,
        gracePeriodSeconds = 30,
    )

    @BeforeEach
    fun setUp() {
        jwtProps = newJwtProps()
        adapter = RefreshTokenJwtAdapter(jwtProps, refreshProps)
    }

    @Test
    fun `발급한 Refresh 토큰을 검증하면 클레임이 반환된다`() {
        val token = adapter.issue("user-123", "family-abc")
        val claims = adapter.verify(token)

        assertEquals("user-123", claims.userId)
        assertEquals("family-abc", claims.family)
        assertTrue(claims.jti.isNotBlank())
    }

    @Test
    fun `Access Token audience로 발급된 JWT는 검증에 실패한다`() {
        val accessAdapter = Rs256JwtAdapter(jwtProps)
        val user = User(
            id = "user-123",
            googleId = "g-1",
            email = "a@b.com",
            name = "Test",
            role = User.Role.USER,
            createdAt = Instant.now(),
        )
        val accessToken = accessAdapter.issue(user)

        assertThrows(InvalidTokenException::class.java) { adapter.verify(accessToken) }
    }

    @Test
    fun `임의의 문자열은 InvalidTokenException을 던진다`() {
        assertThrows(InvalidTokenException::class.java) { adapter.verify("not-a-jwt") }
    }

    private fun newJwtProps(): JwtProperties {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privPem = toPem("PRIVATE KEY", kp.private.encoded)
        val pubPem = toPem("PUBLIC KEY", kp.public.encoded)
        return JwtProperties(
            issuer = "bara-auth",
            audience = "bara-world",
            expirySeconds = 3600,
            privateKeyBase64 = Base64.getEncoder().encodeToString(privPem.toByteArray()),
            publicKeyBase64 = Base64.getEncoder().encodeToString(pubPem.toByteArray()),
        )
    }

    private fun toPem(type: String, bytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val lines = b64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$lines\n-----END $type-----\n"
    }
}
