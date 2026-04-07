package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RefreshTokenUseCase
import com.bara.common.logging.WideEvent
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class RefreshRequest(val refreshToken: String)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@RestController
class RefreshController(
    private val useCase: RefreshTokenUseCase,
) {
    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<RefreshResponse> {
        val tokenPair = useCase.refresh(request.refreshToken)

        WideEvent.put("outcome", "token_refreshed")
        WideEvent.message("토큰 갱신 완료")

        return ResponseEntity.ok(
            RefreshResponse(
                accessToken = tokenPair.accessToken,
                refreshToken = tokenPair.refreshToken,
                expiresIn = 3600,
            )
        )
    }
}
