package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/auth/google")
class AuthController(
    private val useCase: LoginWithGoogleUseCase,
    private val googleProps: GoogleOAuthProperties,
) {

    @GetMapping("/login")
    fun login(): ResponseEntity<Void> {
        val url = useCase.buildLoginUrl()
        WideEvent.put("outcome", "redirect_to_google")
        WideEvent.message("Google 로그인 URL 리다이렉트")
        return redirect(url)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val jwt = useCase.login(code = code, state = state)
        WideEvent.put("outcome", "success")
        WideEvent.message("Google OAuth 콜백 성공")
        return redirect("${frontendCallbackBase()}?token=$jwt")
    }

    private fun redirect(url: String): ResponseEntity<Void> {
        val headers = HttpHeaders().apply { location = URI.create(url) }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String =
        googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
}
