package com.bara.auth.application.port.out

data class RefreshTokenClaims(
    val userId: String,
    val jti: String,
    val family: String,
)

interface RefreshTokenIssuer {
    fun issue(userId: String, family: String): String
    fun verify(token: String): RefreshTokenClaims
}
