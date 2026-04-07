package com.bara.auth.domain.model

sealed class ValidateResult {
    data class UserResult(val userId: String, val role: String) : ValidateResult()
    data class ProviderResult(val providerId: String) : ValidateResult()
}
