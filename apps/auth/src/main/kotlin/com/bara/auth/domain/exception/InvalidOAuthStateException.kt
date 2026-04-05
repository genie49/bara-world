package com.bara.auth.domain.exception

class InvalidOAuthStateException(message: String = "OAuth state 검증 실패") : RuntimeException(message)
