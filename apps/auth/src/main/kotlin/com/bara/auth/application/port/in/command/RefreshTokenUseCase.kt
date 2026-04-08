package com.bara.auth.application.port.`in`.command

import com.bara.auth.domain.model.TokenPair

interface RefreshTokenUseCase {
    fun refresh(refreshToken: String): TokenPair
}
