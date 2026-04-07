package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.ApiKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ApiKeyMongoRepositoryTest {
    private val dataRepo = mockk<ApiKeyMongoDataRepository>(relaxed = true)
    private val repo = ApiKeyMongoRepository(dataRepo)
    private val apiKey = ApiKey(
        id = "k-1", providerId = "p-1", name = "Production",
        keyHash = "hash-abc", keyPrefix = "bk_a3f2e1",
        createdAt = Instant.parse("2026-04-07T00:00:00Z"),
    )

    @Test
    fun `save는 ApiKey를 저장하고 반환한다`() {
        every { dataRepo.save(any()) } returns ApiKeyDocument.fromDomain(apiKey)
        assertEquals(apiKey, repo.save(apiKey))
    }
    @Test
    fun `findByProviderId는 키 목록을 반환한다`() {
        every { dataRepo.findByProviderId("p-1") } returns listOf(ApiKeyDocument.fromDomain(apiKey))
        assertEquals(listOf(apiKey), repo.findByProviderId("p-1"))
    }
    @Test
    fun `countByProviderId는 개수를 반환한다`() {
        every { dataRepo.countByProviderId("p-1") } returns 3L
        assertEquals(3L, repo.countByProviderId("p-1"))
    }
    @Test
    fun `findByKeyHash는 해시로 조회한다`() {
        every { dataRepo.findByKeyHash("hash-abc") } returns ApiKeyDocument.fromDomain(apiKey)
        assertEquals(apiKey, repo.findByKeyHash("hash-abc"))
    }
    @Test
    fun `deleteById는 삭제한다`() {
        repo.deleteById("k-1")
        verify { dataRepo.deleteById("k-1") }
    }
}
