package com.bara.auth.application.port.out

interface JwtVerifier {
    /** 검증 실패 시 com.bara.auth.domain.exception.InvalidTokenException throw */
    fun verify(token: String): JwtClaims
}

data class JwtClaims(
    val userId: String,
    val email: String,
    val role: String,
)
