package com.bara.auth.adapter.out.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.bara.auth.application.port.out.RefreshTokenClaims
import com.bara.auth.application.port.out.RefreshTokenIssuer
import com.bara.auth.config.JwtProperties
import com.bara.auth.config.RefreshTokenProperties
import com.bara.auth.domain.exception.InvalidTokenException
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@Component
class RefreshTokenJwtAdapter(
    private val jwtProps: JwtProperties,
    private val refreshProps: RefreshTokenProperties,
) : RefreshTokenIssuer {

    private val privateKey: RSAPrivateKey = loadPrivateKey(jwtProps.privateKeyPem())
    private val publicKey: RSAPublicKey = loadPublicKey(jwtProps.publicKeyPem())
    private val algorithm: Algorithm = Algorithm.RSA256(publicKey, privateKey)

    private val verifier = JWT.require(algorithm)
        .withIssuer(jwtProps.issuer)
        .withAudience(refreshProps.audience)
        .build()

    override fun issue(userId: String, family: String): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(jwtProps.issuer)
            .withAudience(refreshProps.audience)
            .withSubject(userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(refreshProps.expirySeconds)))
            .withClaim("family", family)
            .sign(algorithm)
    }

    override fun verify(token: String): RefreshTokenClaims {
        try {
            val decoded = verifier.verify(token)
            return RefreshTokenClaims(
                userId = decoded.subject,
                jti = decoded.id,
                family = decoded.getClaim("family").asString(),
            )
        } catch (e: JWTVerificationException) {
            throw InvalidTokenException("Refresh JWT 검증 실패: ${e.message}", e)
        }
    }

    private fun loadPrivateKey(pem: String): RSAPrivateKey {
        val der = pemToDer(pem, "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    private fun loadPublicKey(pem: String): RSAPublicKey {
        val der = pemToDer(pem, "PUBLIC KEY")
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun pemToDer(pem: String, type: String): ByteArray {
        val header = "-----BEGIN $type-----"
        val footer = "-----END $type-----"
        val base64 = pem
            .replace(header, "")
            .replace(footer, "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(base64)
    }
}
