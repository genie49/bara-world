package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import org.springframework.stereotype.Service

@Service
class LoginWithGoogleService(
    private val googleClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val stateStore: OAuthStateStore,
    private val jwtIssuer: JwtIssuer,
) : LoginWithGoogleUseCase {

    override fun buildLoginUrl(): String {
        val state = stateStore.issue()
        return googleClient.buildAuthorizationUrl(state)
    }

    override fun login(code: String, state: String): String {
        stateStore.consume(state)
        val payload = googleClient.exchangeCodeForIdToken(code)
        val user = userRepository.findByGoogleId(payload.googleId)
            ?: userRepository.save(
                User.newUser(
                    googleId = payload.googleId,
                    email = payload.email,
                    name = payload.name,
                )
            )
        return jwtIssuer.issue(user)
    }
}
