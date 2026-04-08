package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.model.Provider
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RegisterProviderServiceTest {
    private val providerRepository = mockk<ProviderRepository>()
    private val service = RegisterProviderService(providerRepository)

    @Test
    fun `신규 Provider를 등록하면 PENDING 상태로 저장된다`() {
        every { providerRepository.findByUserId("u-1") } returns null
        every { providerRepository.save(any()) } answers { firstArg() }

        val result = service.register("u-1", "My Server")

        assertEquals("My Server", result.name)
        assertEquals(Provider.ProviderStatus.PENDING, result.status)
        assertEquals("u-1", result.userId)
        verify { providerRepository.save(any()) }
    }

    @Test
    fun `이미 Provider가 있는 User는 ProviderAlreadyExistsException`() {
        every { providerRepository.findByUserId("u-1") } returns Provider.create("u-1", "Existing")

        assertThrows<ProviderAlreadyExistsException> {
            service.register("u-1", "New")
        }
    }
}
