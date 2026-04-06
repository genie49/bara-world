package com.bara.auth.application.port.out

interface OAuthStateStore {
    /** 랜덤 state 생성 + 저장소에 저장(TTL). 생성된 state 반환. */
    fun issue(): String

    /** state 존재 확인 + 즉시 삭제. 없으면 InvalidOAuthStateException. */
    fun consume(state: String)
}
