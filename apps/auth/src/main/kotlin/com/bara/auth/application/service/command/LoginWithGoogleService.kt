package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class LoginWithGoogleService(
    private val googleClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val stateStore: OAuthStateStore,
    private val jwtIssuer: JwtIssuer,
) : LoginWithGoogleUseCase {

    override fun buildLoginUrl(): String {
        WideEvent.put("oauth_provider", "google")
        val state = stateStore.issue()
        return googleClient.buildAuthorizationUrl(state)
    }

    override fun login(code: String, state: String): String {
        WideEvent.put("oauth_provider", "google")
        stateStore.consume(state)
        val payload = googleClient.exchangeCodeForIdToken(code)
        val existing = userRepository.findByGoogleId(payload.googleId)
        val isNew = existing == null
        val user = existing ?: userRepository.save(
            User.newUser(
                googleId = payload.googleId,
                email = payload.email,
                name = payload.name,
            )
        )
        WideEvent.put("user_id", user.id)
        WideEvent.put("user_email", user.email)
        WideEvent.put("is_new_user", isNew)
        return jwtIssuer.issue(user)
    }
}
