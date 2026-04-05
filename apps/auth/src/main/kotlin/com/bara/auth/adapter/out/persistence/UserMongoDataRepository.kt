package com.bara.auth.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository

interface UserMongoDataRepository : MongoRepository<UserDocument, String> {
    fun findByGoogleId(googleId: String): UserDocument?
}
