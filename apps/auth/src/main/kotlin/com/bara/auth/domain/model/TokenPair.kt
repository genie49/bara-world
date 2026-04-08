package com.bara.auth.domain.model

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
