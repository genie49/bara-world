package com.bara.auth.adapter.out.persistence
import org.springframework.data.mongodb.repository.MongoRepository
interface ProviderMongoDataRepository : MongoRepository<ProviderDocument, String> {
    fun findByUserId(userId: String): ProviderDocument?
}
