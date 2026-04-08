package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class UpdateApiKeyNameServiceTest {
    private val providerRepository = mockk<ProviderRepository>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val service = UpdateApiKeyNameService(providerRepository, apiKeyRepository)

    private val provider = Provider(
        id = "p-1", userId = "u-1", name = "Server",
        status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
    )

    private val apiKey = ApiKey(
        id = "k-1", providerId = "p-1", name = "Old Name",
        keyHash = "hash-abc", keyPrefix = "bk_abc123", createdAt = Instant.now(),
    )

    @Test
    fun `정상적으로 API Key 이름을 수정한다`() {
        every { providerRepository.findByUserId("u-1") } returns provider
        every { apiKeyRepository.findById("k-1") } returns apiKey
        every { apiKeyRepository.save(any()) } answers { firstArg() }

        val result = service.update("u-1", "k-1", "New Name")

        assertEquals("New Name", result.name)
        verify { apiKeyRepository.save(any()) }
    }

    @Test
    fun `Provider가 없으면 ProviderNotFoundException`() {
        every { providerRepository.findByUserId("u-1") } returns null

        assertThrows<ProviderNotFoundException> { service.update("u-1", "k-1", "New Name") }
    }

    @Test
    fun `API Key가 없으면 ApiKeyNotFoundException`() {
        every { providerRepository.findByUserId("u-1") } returns provider
        every { apiKeyRepository.findById("k-1") } returns null

        assertThrows<ApiKeyNotFoundException> { service.update("u-1", "k-1", "New Name") }
    }

    @Test
    fun `소유권 불일치면 ApiKeyNotFoundException`() {
        val otherKey = apiKey.copy(providerId = "p-other")
        every { providerRepository.findByUserId("u-1") } returns provider
        every { apiKeyRepository.findById("k-1") } returns otherKey

        assertThrows<ApiKeyNotFoundException> { service.update("u-1", "k-1", "New Name") }
    }
}
