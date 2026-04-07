package com.bara.auth.e2e.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

object TokenFixture {

    fun expiredJwt(privateKey: RSAPrivateKey, publicKey: RSAPublicKey): String {
        val algorithm = Algorithm.RSA256(publicKey, privateKey)
        val past = Instant.now().minusSeconds(7200)
        return JWT.create()
            .withIssuer("bara-auth")
            .withAudience("bara-world")
            .withSubject("expired-user")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(past))
            .withExpiresAt(Date.from(past.plusSeconds(3600)))
            .withClaim("email", "expired@test.com")
            .withClaim("role", "USER")
            .sign(algorithm)
    }

    fun wrongSignatureJwt(): String {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val now = Instant.now()
        return JWT.create()
            .withIssuer("bara-auth")
            .withAudience("bara-world")
            .withSubject("wrong-sig-user")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .withClaim("email", "wrong@test.com")
            .withClaim("role", "USER")
            .sign(algorithm)
    }
}
