package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.Provider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ProviderMongoRepositoryTest {
    private val dataRepo = mockk<ProviderMongoDataRepository>()
    private val repo = ProviderMongoRepository(dataRepo)
    private val provider = Provider(
        id = "p-1", userId = "u-1", name = "Test",
        status = Provider.ProviderStatus.PENDING, createdAt = Instant.parse("2026-04-07T00:00:00Z"),
    )

    @Test
    fun `save는 Provider를 저장하고 반환한다`() {
        every { dataRepo.save(any()) } returns ProviderDocument.fromDomain(provider)
        assertEquals(provider, repo.save(provider))
    }
    @Test
    fun `findByUserId는 존재하는 Provider를 반환한다`() {
        every { dataRepo.findByUserId("u-1") } returns ProviderDocument.fromDomain(provider)
        assertEquals(provider, repo.findByUserId("u-1"))
    }
    @Test
    fun `findByUserId는 없으면 null`() {
        every { dataRepo.findByUserId("x") } returns null
        assertNull(repo.findByUserId("x"))
    }
    @Test
    fun `findById는 존재하는 Provider를 반환한다`() {
        every { dataRepo.findById("p-1") } returns Optional.of(ProviderDocument.fromDomain(provider))
        assertEquals(provider, repo.findById("p-1"))
    }
}
