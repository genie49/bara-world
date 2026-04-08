package com.bara.auth.application.service.query

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class ListApiKeysServiceTest {
    private val providerRepository = mockk<ProviderRepository>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val service = ListApiKeysService(providerRepository, apiKeyRepository)

    private val provider = Provider(
        id = "p-1", userId = "u-1", name = "Server",
        status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
    )

    private val apiKey = ApiKey(
        id = "k-1", providerId = "p-1", name = "My Key",
        keyHash = "hash-abc", keyPrefix = "bk_abc123", createdAt = Instant.now(),
    )

    @Test
    fun `정상적으로 API Key 목록을 조회한다`() {
        every { providerRepository.findByUserId("u-1") } returns provider
        every { apiKeyRepository.findByProviderId("p-1") } returns listOf(apiKey)

        val result = service.listByUserId("u-1")

        assertEquals(1, result.size)
        assertEquals("k-1", result[0].id)
        verify { apiKeyRepository.findByProviderId("p-1") }
    }

    @Test
    fun `Provider가 없으면 ProviderNotFoundException`() {
        every { providerRepository.findByUserId("u-1") } returns null

        assertThrows<ProviderNotFoundException> { service.listByUserId("u-1") }
    }
}
