package com.bara.auth.adapter.out.persistence
import org.springframework.data.mongodb.repository.MongoRepository
interface ApiKeyMongoDataRepository : MongoRepository<ApiKeyDocument, String> {
    fun findByProviderId(providerId: String): List<ApiKeyDocument>
    fun countByProviderId(providerId: String): Long
    fun findByKeyHash(keyHash: String): ApiKeyDocument?
}
