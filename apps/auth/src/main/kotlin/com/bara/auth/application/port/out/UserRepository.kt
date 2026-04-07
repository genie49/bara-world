package com.bara.auth.application.port.out

import com.bara.auth.domain.model.User

interface UserRepository {
    fun findByGoogleId(googleId: String): User?
    fun findById(id: String): User?
    fun save(user: User): User
}
