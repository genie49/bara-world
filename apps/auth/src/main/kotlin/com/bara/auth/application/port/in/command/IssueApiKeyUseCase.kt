package com.bara.auth.application.port.`in`.command
import com.bara.auth.domain.model.ApiKey
data class IssuedApiKey(val apiKey: ApiKey, val rawKey: String)
interface IssueApiKeyUseCase {
    fun issue(userId: String, name: String): IssuedApiKey
}
