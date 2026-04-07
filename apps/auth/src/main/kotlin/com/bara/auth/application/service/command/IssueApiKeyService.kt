package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.IssueApiKeyUseCase
import com.bara.auth.application.port.`in`.command.IssuedApiKey
import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.*
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class IssueApiKeyService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyGenerator: ApiKeyGenerator,
) : IssueApiKeyUseCase {

    override fun issue(userId: String, name: String): IssuedApiKey {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        if (provider.status != Provider.ProviderStatus.ACTIVE) {
            throw ProviderNotActiveException()
        }
        if (apiKeyRepository.countByProviderId(provider.id) >= 5) {
            throw ApiKeyLimitExceededException()
        }

        val generated = apiKeyGenerator.generate()
        val apiKey = ApiKey.create(
            providerId = provider.id, name = name,
            keyHash = generated.keyHash, keyPrefix = generated.keyPrefix,
        )
        val saved = apiKeyRepository.save(apiKey)

        WideEvent.put("user_id", userId)
        WideEvent.put("provider_id", provider.id)
        WideEvent.put("api_key_id", saved.id)
        WideEvent.put("api_key_prefix", saved.keyPrefix)
        WideEvent.put("outcome", "api_key_issued")
        WideEvent.message("API Key 발급 완료")

        return IssuedApiKey(apiKey = saved, rawKey = generated.rawKey)
    }
}
