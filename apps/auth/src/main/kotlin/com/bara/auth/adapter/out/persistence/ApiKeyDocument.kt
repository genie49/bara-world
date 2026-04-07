package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.ApiKey
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "api_keys")
data class ApiKeyDocument(
    @Id val id: String,
    @Indexed val providerId: String,
    val name: String,
    @Indexed(unique = true) val keyHash: String,
    val keyPrefix: String,
    val createdAt: Instant,
) {
    fun toDomain(): ApiKey = ApiKey(
        id = id, providerId = providerId, name = name,
        keyHash = keyHash, keyPrefix = keyPrefix, createdAt = createdAt,
    )
    companion object {
        fun fromDomain(k: ApiKey): ApiKeyDocument = ApiKeyDocument(
            id = k.id, providerId = k.providerId, name = k.name,
            keyHash = k.keyHash, keyPrefix = k.keyPrefix, createdAt = k.createdAt,
        )
    }
}
