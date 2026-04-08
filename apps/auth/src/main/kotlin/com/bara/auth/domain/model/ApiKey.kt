package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class ApiKey(
    val id: String,
    val providerId: String,
    val name: String,
    val keyHash: String,
    val keyPrefix: String,
    val createdAt: Instant,
) {
    companion object {
        fun create(
            providerId: String, name: String,
            keyHash: String, keyPrefix: String,
            now: Instant = Instant.now(),
        ): ApiKey = ApiKey(
            id = UUID.randomUUID().toString(),
            providerId = providerId, name = name,
            keyHash = keyHash, keyPrefix = keyPrefix, createdAt = now,
        )
    }
}
