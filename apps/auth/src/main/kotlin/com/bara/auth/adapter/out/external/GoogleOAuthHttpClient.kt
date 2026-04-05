package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class GoogleOAuthHttpClient(
    private val props: GoogleOAuthProperties,
) : GoogleOAuthClient {

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private val verifier: GoogleIdTokenVerifier = GoogleIdTokenVerifier.Builder(transport, jsonFactory)
        .setAudience(listOf(props.clientId))
        .build()

    override fun buildAuthorizationUrl(state: String): String {
        val params = mapOf(
            "client_id" to props.clientId,
            "redirect_uri" to props.redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "state" to state,
            "access_type" to "online",
            "prompt" to "select_account",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    override fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload {
        val tokenResponse = try {
            GoogleAuthorizationCodeTokenRequest(
                transport,
                jsonFactory,
                props.clientId,
                props.clientSecret,
                code,
                props.redirectUri,
            ).execute()
        } catch (e: TokenResponseException) {
            throw GoogleExchangeFailedException("Google code 교환 실패: ${e.details?.error ?: e.message}", e)
        } catch (e: Exception) {
            throw GoogleExchangeFailedException("Google code 교환 중 예외: ${e.message}", e)
        }

        val idTokenString: String = tokenResponse.idToken
            ?: throw InvalidIdTokenException("Google 응답에 id_token이 없음")

        val idToken: GoogleIdToken = try {
            verifier.verify(idTokenString)
                ?: throw InvalidIdTokenException("ID token 검증 실패 (서명 또는 audience 불일치)")
        } catch (e: Exception) {
            if (e is InvalidIdTokenException) throw e
            throw InvalidIdTokenException("ID token 검증 중 예외: ${e.message}", e)
        }

        val payload = idToken.payload
        return GoogleIdTokenPayload(
            googleId = payload.subject,
            email = payload.email ?: throw InvalidIdTokenException("ID token에 email 클레임 없음"),
            name = (payload["name"] as? String) ?: "",
        )
    }
}
