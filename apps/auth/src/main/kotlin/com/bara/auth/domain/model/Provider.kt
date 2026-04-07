package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class Provider(
    val id: String,
    val userId: String,
    val name: String,
    val status: ProviderStatus,
    val createdAt: Instant,
) {
    enum class ProviderStatus {
        PENDING, ACTIVE, SUSPENDED
    }

    companion object {
        fun create(userId: String, name: String, now: Instant = Instant.now()): Provider =
            Provider(
                id = UUID.randomUUID().toString(),
                userId = userId,
                name = name,
                status = ProviderStatus.PENDING,
                createdAt = now,
            )
    }
}
