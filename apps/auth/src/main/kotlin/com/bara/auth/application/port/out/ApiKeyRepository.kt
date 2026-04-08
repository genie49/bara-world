package com.bara.auth.application.port.out
import com.bara.auth.domain.model.ApiKey
interface ApiKeyRepository {
    fun save(apiKey: ApiKey): ApiKey
    fun findByProviderId(providerId: String): List<ApiKey>
    fun findById(id: String): ApiKey?
    fun countByProviderId(providerId: String): Long
    fun deleteById(id: String)
    fun findByKeyHash(keyHash: String): ApiKey?
}
