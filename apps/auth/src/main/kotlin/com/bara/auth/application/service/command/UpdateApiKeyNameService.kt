package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.UpdateApiKeyNameUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class UpdateApiKeyNameService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : UpdateApiKeyNameUseCase {
    override fun update(userId: String, keyId: String, newName: String): ApiKey {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        val apiKey = apiKeyRepository.findById(keyId)
            ?: throw ApiKeyNotFoundException()
        if (apiKey.providerId != provider.id) throw ApiKeyNotFoundException()

        val updated = apiKey.copy(name = newName)
        val saved = apiKeyRepository.save(updated)

        WideEvent.put("user_id", userId)
        WideEvent.put("api_key_id", keyId)
        WideEvent.put("outcome", "api_key_name_updated")
        WideEvent.message("API Key 이름 수정")

        return saved
    }
}
