package com.bara.auth.application.service.query

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import com.bara.auth.domain.model.ValidateResult
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant

class ValidateTokenServiceTest {

    private val jwtVerifier = mockk<JwtVerifier>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val providerRepository = mockk<ProviderRepository>()
    private val service = ValidateTokenService(jwtVerifier, apiKeyRepository, providerRepository)

    private val provider = Provider(
        id = "p-1", userId = "u-1", name = "Server",
        status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
    )

    private val rawApiKey = "bk_test_api_key_123"
    private val keyHash = sha256(rawApiKey)

    private val apiKey = ApiKey(
        id = "k-1", providerId = "p-1", name = "My Key",
        keyHash = keyHash, keyPrefix = "bk_test_a", createdAt = Instant.now(),
    )

    @Test
    fun `User JWT 검증 성공 시 UserResult 반환`() {
        every { jwtVerifier.verify("valid-jwt") } returns JwtClaims(
            userId = "u-1", email = "test@test.com", role = "USER",
        )

        val result = service.validate("valid-jwt")

        assertThat(result).isInstanceOf(ValidateResult.UserResult::class.java)
        val userResult = result as ValidateResult.UserResult
        assertThat(userResult.userId).isEqualTo("u-1")
        assertThat(userResult.role).isEqualTo("USER")
    }

    @Test
    fun `API Key 검증 성공 시 ProviderResult 반환`() {
        every { apiKeyRepository.findByKeyHash(keyHash) } returns apiKey
        every { providerRepository.findById("p-1") } returns provider

        val result = service.validate(rawApiKey)

        assertThat(result).isInstanceOf(ValidateResult.ProviderResult::class.java)
        val providerResult = result as ValidateResult.ProviderResult
        assertThat(providerResult.providerId).isEqualTo("p-1")
    }

    @Test
    fun `API Key가 존재하지 않으면 InvalidTokenException`() {
        every { apiKeyRepository.findByKeyHash(any()) } returns null

        assertThatThrownBy { service.validate(rawApiKey) }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    @Test
    fun `API Key의 Provider가 ACTIVE가 아니면 InvalidTokenException`() {
        val suspendedProvider = provider.copy(status = Provider.ProviderStatus.SUSPENDED)
        every { apiKeyRepository.findByKeyHash(keyHash) } returns apiKey
        every { providerRepository.findById("p-1") } returns suspendedProvider

        assertThatThrownBy { service.validate(rawApiKey) }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    @Test
    fun `잘못된 JWT 토큰이면 InvalidTokenException`() {
        every { jwtVerifier.verify("bad-jwt") } throws InvalidTokenException("Invalid token")

        assertThatThrownBy { service.validate("bad-jwt") }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
