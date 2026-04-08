package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import org.springframework.stereotype.Repository

@Repository
class ProviderMongoRepository(
    private val dataRepository: ProviderMongoDataRepository,
) : ProviderRepository {
    override fun save(provider: Provider): Provider =
        dataRepository.save(ProviderDocument.fromDomain(provider)).toDomain()
    override fun findByUserId(userId: String): Provider? =
        dataRepository.findByUserId(userId)?.toDomain()
    override fun findById(id: String): Provider? =
        dataRepository.findById(id).orElse(null)?.toDomain()
}
