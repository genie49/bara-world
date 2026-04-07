package com.bara.auth.application.service.query

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GetProviderServiceTest {

    private val providerRepository = mockk<ProviderRepository>()
    private val sut = GetProviderService(providerRepository)

    @Test
    fun `등록된 Provider가 있으면 반환한다`() {
        val provider = Provider(
            id = "p1",
            userId = "u1",
            name = "test",
            status = Provider.ProviderStatus.ACTIVE,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        every { providerRepository.findByUserId("u1") } returns provider

        val result = sut.getByUserId("u1")

        assertThat(result).isEqualTo(provider)
    }

    @Test
    fun `등록된 Provider가 없으면 null을 반환한다`() {
        every { providerRepository.findByUserId("u1") } returns null

        val result = sut.getByUserId("u1")

        assertThat(result).isNull()
    }
}
