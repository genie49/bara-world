package com.bara.auth.application.port.out

interface GoogleOAuthClient {
    /** Google 인증 URL 조립 (scope: openid email profile). */
    fun buildAuthorizationUrl(state: String): String

    /**
     * Authorization code를 ID token으로 교환 후 서명 검증한 페이로드 반환.
     *
     * @throws com.bara.auth.domain.exception.GoogleExchangeFailedException code 교환 실패
     * @throws com.bara.auth.domain.exception.InvalidIdTokenException ID token 서명/클레임 검증 실패
     */
    fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload
}

data class GoogleIdTokenPayload(
    val googleId: String,
    val email: String,
    val name: String,
)
