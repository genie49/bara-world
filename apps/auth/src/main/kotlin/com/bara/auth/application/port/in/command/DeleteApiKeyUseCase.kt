package com.bara.auth.application.port.`in`.command
interface DeleteApiKeyUseCase {
    fun delete(userId: String, keyId: String)
}
