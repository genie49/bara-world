package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.Provider
import com.bara.auth.domain.model.ValidateResult
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class ValidateTokenService(
    private val jwtVerifier: JwtVerifier,
    private val apiKeyRepository: ApiKeyRepository,
    private val providerRepository: ProviderRepository,
) : ValidateTokenUseCase {

    override fun validate(token: String): ValidateResult {
        if (token.startsWith("bk_")) {
            return validateApiKey(token)
        }
        return validateJwt(token)
    }

    private fun validateJwt(token: String): ValidateResult.UserResult {
        val claims = jwtVerifier.verify(token)
        WideEvent.put("user_id", claims.userId)
        WideEvent.put("token_type", "user_jwt")
        WideEvent.put("outcome", "token_valid")
        WideEvent.message("User JWT 검증 성공")
        return ValidateResult.UserResult(userId = claims.userId, role = claims.role)
    }

    private fun validateApiKey(rawKey: String): ValidateResult.ProviderResult {
        val keyHash = sha256(rawKey)
        val apiKey = apiKeyRepository.findByKeyHash(keyHash)
            ?: throw InvalidTokenException("API Key not found")

        val provider = providerRepository.findById(apiKey.providerId)
            ?: throw InvalidTokenException("Provider not found")

        if (provider.status != Provider.ProviderStatus.ACTIVE) {
            throw InvalidTokenException("Provider is not active")
        }

        WideEvent.put("provider_id", provider.id)
        WideEvent.put("api_key_id", apiKey.id)
        WideEvent.put("token_type", "api_key")
        WideEvent.put("outcome", "token_valid")
        WideEvent.message("API Key 검증 성공")
        return ValidateResult.ProviderResult(providerId = provider.id)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
