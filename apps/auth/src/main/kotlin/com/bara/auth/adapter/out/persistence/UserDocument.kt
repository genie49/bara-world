package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "users")
data class UserDocument(
    @Id val id: String,
    @Indexed(unique = true) val googleId: String,
    val email: String,
    val name: String,
    val role: String,
    val createdAt: Instant,
) {
    fun toDomain(): User = User(
        id = id,
        googleId = googleId,
        email = email,
        name = name,
        role = User.Role.valueOf(role),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(user: User): UserDocument = UserDocument(
            id = user.id,
            googleId = user.googleId,
            email = user.email,
            name = user.name,
            role = user.role.name,
            createdAt = user.createdAt,
        )
    }
}
