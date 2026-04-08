package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.domain.model.ApiKey
import org.springframework.stereotype.Repository

@Repository
class ApiKeyMongoRepository(
    private val dataRepository: ApiKeyMongoDataRepository,
) : ApiKeyRepository {
    override fun save(apiKey: ApiKey): ApiKey =
        dataRepository.save(ApiKeyDocument.fromDomain(apiKey)).toDomain()
    override fun findByProviderId(providerId: String): List<ApiKey> =
        dataRepository.findByProviderId(providerId).map { it.toDomain() }
    override fun findById(id: String): ApiKey? =
        dataRepository.findById(id).orElse(null)?.toDomain()
    override fun countByProviderId(providerId: String): Long =
        dataRepository.countByProviderId(providerId)
    override fun deleteById(id: String) =
        dataRepository.deleteById(id)
    override fun findByKeyHash(keyHash: String): ApiKey? =
        dataRepository.findByKeyHash(keyHash)?.toDomain()
}
