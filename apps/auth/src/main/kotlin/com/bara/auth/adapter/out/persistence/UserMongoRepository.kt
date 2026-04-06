package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import org.springframework.stereotype.Repository

@Repository
class UserMongoRepository(
    private val dataRepository: UserMongoDataRepository,
) : UserRepository {
    override fun findByGoogleId(googleId: String): User? =
        dataRepository.findByGoogleId(googleId)?.toDomain()

    override fun save(user: User): User =
        dataRepository.save(UserDocument.fromDomain(user)).toDomain()
}
