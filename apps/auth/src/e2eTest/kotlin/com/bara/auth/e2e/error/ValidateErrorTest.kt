package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import com.bara.auth.e2e.support.TokenFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class ValidateErrorTest : E2eTestBase() {

    @Value("\${bara.auth.jwt.private-key-base64}")
    lateinit var privateKeyBase64: String

    @Value("\${bara.auth.jwt.public-key-base64}")
    lateinit var publicKeyBase64: String

    @Test
    fun `Authorization 헤더 없으면 401`() {
        val response = rest.exchange(
            "/validate",
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `잘못된 서명의 JWT는 401`() {
        val jwt = TokenFixture.wrongSignatureJwt()
        val headers = HttpHeaders().apply { setBearerAuth(jwt) }
        val response = rest.exchange(
            "/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `만료된 JWT는 401`() {
        val (privKey, pubKey) = loadRsaKeys()
        val jwt = TokenFixture.expiredJwt(privKey, pubKey)
        val headers = HttpHeaders().apply { setBearerAuth(jwt) }
        val response = rest.exchange(
            "/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `존재하지 않는 API Key는 401`() {
        val headers = HttpHeaders().apply { setBearerAuth("bk_nonexistent_key_12345678") }
        val response = rest.exchange(
            "/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `SUSPENDED Provider의 API Key는 401`() {
        val providerId = "suspended-provider-id"
        val keyHash = sha256("bk_suspended_test_key_123")

        mongoTemplate.db.getCollection("providers").insertOne(
            org.bson.Document(mapOf(
                "_id" to providerId,
                "userId" to "suspended-user",
                "name" to "Suspended Provider",
                "status" to "SUSPENDED",
                "createdAt" to java.time.Instant.now(),
            ))
        )
        mongoTemplate.db.getCollection("api_keys").insertOne(
            org.bson.Document(mapOf(
                "_id" to "suspended-key-id",
                "providerId" to providerId,
                "name" to "Suspended Key",
                "keyHash" to keyHash,
                "keyPrefix" to "bk_suspen",
                "createdAt" to java.time.Instant.now(),
            ))
        )

        val headers = HttpHeaders().apply { setBearerAuth("bk_suspended_test_key_123") }
        val response = rest.exchange(
            "/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun loadRsaKeys(): Pair<RSAPrivateKey, RSAPublicKey> {
        val privPem = String(Base64.getDecoder().decode(privateKeyBase64))
        val pubPem = String(Base64.getDecoder().decode(publicKeyBase64))
        val kf = KeyFactory.getInstance("RSA")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(pemToDer(privPem, "PRIVATE KEY"))) as RSAPrivateKey
        val pubKey = kf.generatePublic(X509EncodedKeySpec(pemToDer(pubPem, "PUBLIC KEY"))) as RSAPublicKey
        return privKey to pubKey
    }

    private fun pemToDer(pem: String, type: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(base64)
    }

    private fun sha256(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
