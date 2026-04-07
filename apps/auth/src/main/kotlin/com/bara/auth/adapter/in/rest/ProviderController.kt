package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.out.JwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val jwtVerifier: JwtVerifier,
) {
    @PostMapping("/register")
    fun register(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: RegisterProviderRequest,
    ): ResponseEntity<ProviderResponse> {
        val userId = extractUserId(authorization)
        val provider = registerUseCase.register(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ProviderResponse(
                id = provider.id,
                name = provider.name,
                status = provider.status.name,
                createdAt = provider.createdAt.toString(),
            )
        )
    }

    private fun extractUserId(authorization: String): String {
        val token = authorization.removePrefix("Bearer ").trim()
        return jwtVerifier.verify(token).userId
    }
}

data class RegisterProviderRequest(val name: String)
data class ProviderResponse(val id: String, val name: String, val status: String, val createdAt: String)
