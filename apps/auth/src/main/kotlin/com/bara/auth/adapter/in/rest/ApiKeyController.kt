package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.DeleteApiKeyUseCase
import com.bara.auth.application.port.`in`.command.IssueApiKeyUseCase
import com.bara.auth.application.port.`in`.command.UpdateApiKeyNameUseCase
import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
import com.bara.auth.application.port.out.JwtVerifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/provider/api-key")
class ApiKeyController(
    private val issueUseCase: IssueApiKeyUseCase,
    private val listQuery: ListApiKeysQuery,
    private val updateUseCase: UpdateApiKeyNameUseCase,
    private val deleteUseCase: DeleteApiKeyUseCase,
    private val jwtVerifier: JwtVerifier,
) {
    @PostMapping
    fun issue(
        @RequestHeader("Authorization") auth: String,
        @RequestBody request: IssueApiKeyRequest,
    ): ResponseEntity<IssuedApiKeyResponse> {
        val userId = extractUserId(auth)
        val result = issueUseCase.issue(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssuedApiKeyResponse(
                id = result.apiKey.id,
                name = result.apiKey.name,
                apiKey = result.rawKey,
                prefix = result.apiKey.keyPrefix,
                createdAt = result.apiKey.createdAt.toString(),
            )
        )
    }

    @GetMapping
    fun list(
        @RequestHeader("Authorization") auth: String,
    ): ResponseEntity<ApiKeyListResponse> {
        val userId = extractUserId(auth)
        val keys = listQuery.listByUserId(userId)
        return ResponseEntity.ok(ApiKeyListResponse(keys = keys.map {
            ApiKeyResponse(id = it.id, name = it.name, prefix = it.keyPrefix, createdAt = it.createdAt.toString())
        }))
    }

    @PatchMapping("/{keyId}")
    fun updateName(
        @RequestHeader("Authorization") auth: String,
        @PathVariable keyId: String,
        @RequestBody request: UpdateApiKeyNameRequest,
    ): ResponseEntity<ApiKeyResponse> {
        val userId = extractUserId(auth)
        val updated = updateUseCase.update(userId, keyId, request.name)
        return ResponseEntity.ok(
            ApiKeyResponse(
                id = updated.id,
                name = updated.name,
                prefix = updated.keyPrefix,
                createdAt = updated.createdAt.toString(),
            )
        )
    }

    @DeleteMapping("/{keyId}")
    fun delete(
        @RequestHeader("Authorization") auth: String,
        @PathVariable keyId: String,
    ): ResponseEntity<Void> {
        val userId = extractUserId(auth)
        deleteUseCase.delete(userId, keyId)
        return ResponseEntity.noContent().build()
    }

    private fun extractUserId(authorization: String): String {
        val token = authorization.removePrefix("Bearer ").trim()
        return jwtVerifier.verify(token).userId
    }
}

data class IssueApiKeyRequest(val name: String)
data class UpdateApiKeyNameRequest(val name: String)
data class ApiKeyResponse(val id: String, val name: String, val prefix: String, val createdAt: String)
data class IssuedApiKeyResponse(val id: String, val name: String, val apiKey: String, val prefix: String, val createdAt: String)
data class ApiKeyListResponse(val keys: List<ApiKeyResponse>)
