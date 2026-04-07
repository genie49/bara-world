package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.`in`.query.GetProviderQuery
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val getProviderQuery: GetProviderQuery,
) {
    @GetMapping
    fun get(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<ProviderResponse> {
        val provider = getProviderQuery.getByUserId(userId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ProviderResponse(
                id = provider.id,
                name = provider.name,
                status = provider.status.name,
                createdAt = provider.createdAt.toString(),
            )
        )
    }

    @PostMapping("/register")
    fun register(
        @RequestHeader("X-User-Id") userId: String,
        @RequestBody request: RegisterProviderRequest,
    ): ResponseEntity<ProviderResponse> {
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
}

data class RegisterProviderRequest(val name: String)
data class ProviderResponse(val id: String, val name: String, val status: String, val createdAt: String)
