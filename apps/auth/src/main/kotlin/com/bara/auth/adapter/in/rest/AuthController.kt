package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.common.logging.WideEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@Tag(name = "Google OAuth", description = "Google 소셜 로그인 (브라우저 리다이렉트)")
@RestController
@RequestMapping("/google")
class AuthController(
    private val useCase: LoginWithGoogleUseCase,
    private val googleProps: GoogleOAuthProperties,
) {

    @Operation(
        summary = "Google 로그인 페이지로 리다이렉트",
        description = "브라우저 리다이렉트 방식. Swagger UI에서 직접 테스트 불가. 브라우저에서 직접 접속하면 Google 로그인 페이지로 이동한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "302", description = "Google 로그인 페이지로 리다이렉트 (Location 헤더)"),
        ],
    )
    @GetMapping("/login")
    fun login(): ResponseEntity<Void> {
        val url = useCase.buildLoginUrl()
        WideEvent.put("outcome", "redirect_to_google")
        WideEvent.message("Google 로그인 URL 리다이렉트")
        return redirect(url)
    }

    @Operation(
        summary = "Google OAuth 콜백 처리",
        description = "Google이 호출하는 콜백 엔드포인트. 직접 호출할 필요 없음. 인증 성공 시 프론트엔드로 토큰과 함께 리다이렉트한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "302", description = "프론트엔드로 토큰과 함께 리다이렉트"),
        ],
    )
    @GetMapping("/callback")
    fun callback(
        @Parameter(description = "Google에서 전달한 인증 코드", example = "4/0AX4XfWh...")
        @RequestParam code: String,
        @Parameter(description = "CSRF 방지용 state 값", example = "random-state-string")
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val tokenPair = useCase.login(code = code, state = state)
        WideEvent.put("outcome", "success")
        WideEvent.message("Google OAuth 콜백 성공")
        return redirect("${frontendCallbackBase()}?token=${tokenPair.accessToken}&refreshToken=${tokenPair.refreshToken}")
    }

    private fun redirect(url: String): ResponseEntity<Void> {
        val headers = HttpHeaders().apply { location = URI.create(url) }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String =
        googleProps.redirectUri.replace("/api/auth/google/callback", "/auth/callback")
}
