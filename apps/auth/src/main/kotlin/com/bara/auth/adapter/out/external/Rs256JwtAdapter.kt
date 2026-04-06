package com.bara.auth.adapter.out.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.config.JwtProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
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
class Rs256JwtAdapter(
    private val props: JwtProperties,
) : JwtIssuer, JwtVerifier {

    private val privateKey: RSAPrivateKey = loadPrivateKey(props.privateKeyPem())
    private val publicKey: RSAPublicKey = loadPublicKey(props.publicKeyPem())
    private val algorithm: Algorithm = Algorithm.RSA256(publicKey, privateKey)

    private val verifier = JWT.require(algorithm)
        .withIssuer(props.issuer)
        .withAudience(props.audience)
        .build()

    override fun issue(user: User): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(props.issuer)
            .withAudience(props.audience)
            .withSubject(user.id)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(props.expirySeconds)))
            .withClaim("email", user.email)
            .withClaim("role", user.role.name)
            .sign(algorithm)
    }

    override fun verify(token: String): JwtClaims {
        try {
            val decoded = verifier.verify(token)
            return JwtClaims(
                userId = decoded.subject,
                email = decoded.getClaim("email").asString(),
                role = decoded.getClaim("role").asString(),
            )
        } catch (e: JWTVerificationException) {
            throw InvalidTokenException("JWT 검증 실패: ${e.message}", e)
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
