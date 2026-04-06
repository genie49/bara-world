package com.bara.auth.adapter.out.external

import com.bara.auth.config.JwtProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

class Rs256JwtAdapterTest {

    private lateinit var adapter: Rs256JwtAdapter

    private val user = User(
        id = "user-123",
        googleId = "google-abc",
        email = "test@example.com",
        name = "Test User",
        role = User.Role.USER,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @BeforeEach
    fun setUp() {
        adapter = Rs256JwtAdapter(newProps())
    }

    @Test
    fun `발급한 토큰을 검증하면 클레임이 반환된다`() {
        val token = adapter.issue(user)
        val claims = adapter.verify(token)
        assertEquals("user-123", claims.userId)
        assertEquals("test@example.com", claims.email)
        assertEquals("USER", claims.role)
    }

    @Test
    fun `잘못된 서명의 토큰은 InvalidTokenException을 던진다`() {
        val token = adapter.issue(user)
        val tampered = token.dropLast(4) + "AAAA"
        assertThrows(InvalidTokenException::class.java) { adapter.verify(tampered) }
    }

    @Test
    fun `다른 발급자로 서명된 토큰은 InvalidTokenException을 던진다`() {
        val otherAdapter = Rs256JwtAdapter(newProps())
        val tokenFromOther = otherAdapter.issue(user)
        assertThrows(InvalidTokenException::class.java) { adapter.verify(tokenFromOther) }
    }

    @Test
    fun `임의의 문자열은 InvalidTokenException을 던진다`() {
        assertThrows(InvalidTokenException::class.java) { adapter.verify("not-a-jwt") }
    }

    private fun newProps(): JwtProperties {
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
