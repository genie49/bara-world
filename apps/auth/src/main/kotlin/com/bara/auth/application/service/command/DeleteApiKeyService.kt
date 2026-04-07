package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.DeleteApiKeyUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class DeleteApiKeyService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : DeleteApiKeyUseCase {
    override fun delete(userId: String, keyId: String) {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        val apiKey = apiKeyRepository.findById(keyId)
            ?: throw ApiKeyNotFoundException()
        if (apiKey.providerId != provider.id) throw ApiKeyNotFoundException()

        apiKeyRepository.deleteById(keyId)

        WideEvent.put("user_id", userId)
        WideEvent.put("api_key_id", keyId)
        WideEvent.put("api_key_prefix", apiKey.keyPrefix)
        WideEvent.put("outcome", "api_key_deleted")
        WideEvent.message("API Key 삭제")
    }
}
