package com.bara.auth.application.port.out

interface RefreshTokenStore {
    fun save(userId: String, jti: String, family: String)
    fun find(userId: String): StoredRefreshToken?
    fun delete(userId: String)
    fun saveGrace(jti: String)
    fun isGraceValid(jti: String): Boolean
}

data class StoredRefreshToken(
    val jti: String,
    val family: String,
)
