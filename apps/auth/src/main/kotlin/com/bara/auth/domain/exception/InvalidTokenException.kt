package com.bara.auth.domain.exception

class InvalidTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
