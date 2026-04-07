package com.bara.auth.application.port.`in`.command
import com.bara.auth.domain.model.ApiKey
interface UpdateApiKeyNameUseCase {
    fun update(userId: String, keyId: String, newName: String): ApiKey
}
