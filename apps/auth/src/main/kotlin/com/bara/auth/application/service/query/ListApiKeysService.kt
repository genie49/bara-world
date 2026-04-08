package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.auth.domain.model.ApiKey
import org.springframework.stereotype.Service

@Service
class ListApiKeysService(
    private val providerRepository: ProviderRepository,
    private val apiKeyRepository: ApiKeyRepository,
) : ListApiKeysQuery {
    override fun listByUserId(userId: String): List<ApiKey> {
        val provider = providerRepository.findByUserId(userId)
            ?: throw ProviderNotFoundException()
        return apiKeyRepository.findByProviderId(provider.id)
    }
}
