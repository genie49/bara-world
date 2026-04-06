package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class User(
    val id: String,
    val googleId: String,
    val email: String,
    val name: String,
    val role: Role,
    val createdAt: Instant,
) {
    enum class Role { USER, ADMIN }

    companion object {
        fun newUser(
            googleId: String,
            email: String,
            name: String,
            now: Instant = Instant.now(),
        ): User = User(
            id = UUID.randomUUID().toString(),
            googleId = googleId,
            email = email,
            name = name,
            role = Role.USER,
            createdAt = now,
        )
    }
}
