package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.RefreshTokenUseCase
import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.TokenPair
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RefreshTokenService(
    private val refreshTokenIssuer: RefreshTokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
    private val jwtIssuer: JwtIssuer,
    private val userRepository: UserRepository,
) : RefreshTokenUseCase {

    override fun refresh(refreshToken: String): TokenPair {
        val claims = refreshTokenIssuer.verify(refreshToken)
        val stored = refreshTokenStore.find(claims.userId)
            ?: throw InvalidTokenException("Refresh token not found")

        WideEvent.put("user_id", claims.userId)
        WideEvent.put("token_family", stored.family)

        if (claims.jti != stored.jti) {
            if (refreshTokenStore.isGraceValid(claims.jti)) {
                WideEvent.put("grace_period_used", true)
                WideEvent.message("Grace period 내 이전 Refresh Token 사용 허용")
            } else {
                WideEvent.put("reuse_detected_family", stored.family)
                WideEvent.put("outcome", "reuse_detected")
                WideEvent.message("Refresh Token 재사용 감지 — family 무효화")
                refreshTokenStore.delete(claims.userId)
                throw InvalidTokenException("Refresh token reuse detected")
            }
        }

        val user = userRepository.findById(claims.userId)
            ?: throw InvalidTokenException("User not found")

        val newAccessToken = jwtIssuer.issue(user)
        val newRefreshToken = refreshTokenIssuer.issue(claims.userId, stored.family)
        val newClaims = refreshTokenIssuer.verify(newRefreshToken)

        refreshTokenStore.saveGrace(claims.jti)
        refreshTokenStore.save(claims.userId, newClaims.jti, stored.family)

        WideEvent.put("user_email", user.email)
        WideEvent.put("outcome", "token_refreshed")
        WideEvent.message("토큰 갱신 성공")

        return TokenPair(accessToken = newAccessToken, refreshToken = newRefreshToken)
    }
}
