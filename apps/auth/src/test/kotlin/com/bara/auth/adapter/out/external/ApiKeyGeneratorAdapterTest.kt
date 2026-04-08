package com.bara.auth.adapter.out.external

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiKeyGeneratorAdapterTest {
    private val generator = ApiKeyGeneratorAdapter()

    @Test
    fun `생성된 키는 bk_ 접두사로 시작하고 67자이다`() {
        val result = generator.generate()
        assertTrue(result.rawKey.startsWith("bk_"))
        assertEquals(67, result.rawKey.length)
    }

    @Test
    fun `keyPrefix는 rawKey의 앞 10자이다`() {
        val result = generator.generate()
        assertEquals(result.rawKey.substring(0, 10), result.keyPrefix)
    }

    @Test
    fun `keyHash는 rawKey의 SHA-256 해시이다`() {
        val result = generator.generate()
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(result.rawKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, result.keyHash)
    }

    @Test
    fun `두 번 생성하면 서로 다른 키가 나온다`() {
        val key1 = generator.generate()
        val key2 = generator.generate()
        assertNotEquals(key1.rawKey, key2.rawKey)
    }
}
