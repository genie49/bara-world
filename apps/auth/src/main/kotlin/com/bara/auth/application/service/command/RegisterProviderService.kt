package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.model.Provider
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RegisterProviderService(
    private val providerRepository: ProviderRepository,
) : RegisterProviderUseCase {

    override fun register(userId: String, name: String): Provider {
        providerRepository.findByUserId(userId)?.let {
            throw ProviderAlreadyExistsException()
        }
        val provider = Provider.create(userId = userId, name = name)
        val saved = providerRepository.save(provider)

        WideEvent.put("provider_id", saved.id)
        WideEvent.put("provider_name", saved.name)
        WideEvent.put("user_id", userId)
        WideEvent.put("outcome", "provider_registered")
        WideEvent.message("Provider 등록 완료 (PENDING)")

        return saved
    }
}
