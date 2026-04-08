package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.*
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class IssueApiKeyServiceTest {
    private val providerRepository = mockk<ProviderRepository>()
    private val apiKeyRepository = mockk<ApiKeyRepository>(relaxed = true)
    private val apiKeyGenerator = mockk<ApiKeyGenerator>()
    private val service = IssueApiKeyService(providerRepository, apiKeyRepository, apiKeyGenerator)

    private val activeProvider = Provider(
        id = "p-1", userId = "u-1", name = "Server",
        status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
    )

    @Test
    fun `ACTIVE Provider에 API Key를 발급한다`() {
        every { providerRepository.findByUserId("u-1") } returns activeProvider
        every { apiKeyRepository.countByProviderId("p-1") } returns 0L
        every { apiKeyGenerator.generate() } returns GeneratedApiKey(
            rawKey = "bk_abc12345..rest", keyHash = "hash-abc", keyPrefix = "bk_abc123",
        )
        every { apiKeyRepository.save(any()) } answers { firstArg() }

        val result = service.issue("u-1", "Prod Key")

        assertEquals("bk_abc12345..rest", result.rawKey)
        assertEquals("Prod Key", result.apiKey.name)
        verify { apiKeyRepository.save(any()) }
    }

    @Test
    fun `Provider가 없으면 ProviderNotFoundException`() {
        every { providerRepository.findByUserId("u-1") } returns null
        assertThrows<ProviderNotFoundException> { service.issue("u-1", "Key") }
    }

    @Test
    fun `Provider가 PENDING이면 ProviderNotActiveException`() {
        val pending = activeProvider.copy(status = Provider.ProviderStatus.PENDING)
        every { providerRepository.findByUserId("u-1") } returns pending
        assertThrows<ProviderNotActiveException> { service.issue("u-1", "Key") }
    }

    @Test
    fun `API Key가 5개면 ApiKeyLimitExceededException`() {
        every { providerRepository.findByUserId("u-1") } returns activeProvider
        every { apiKeyRepository.countByProviderId("p-1") } returns 5L
        assertThrows<ApiKeyLimitExceededException> { service.issue("u-1", "Key") }
    }
}
