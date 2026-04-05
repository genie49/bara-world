package com.bara.auth.application.port.`in`.command

interface LoginWithGoogleUseCase {
    /** state와 code를 검증하고 자체 JWT를 반환한다. */
    fun login(code: String, state: String): String

    /** Google 인증 URL을 조립한다 (state 생성 포함). */
    fun buildLoginUrl(): String
}
