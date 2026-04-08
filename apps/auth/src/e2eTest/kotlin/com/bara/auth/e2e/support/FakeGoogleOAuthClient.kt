package com.bara.auth.e2e.support

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Component
@Primary
class FakeGoogleOAuthClient : GoogleOAuthClient {

    var nextPayload: GoogleIdTokenPayload = GoogleIdTokenPayload(
        googleId = "google-e2e-user-001",
        email = "e2e@test.com",
        name = "E2E User",
    )

    override fun buildAuthorizationUrl(state: String): String =
        "http://localhost/fake-google?state=$state"

    override fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload = nextPayload
}
