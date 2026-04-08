package com.bara.auth.adapter.out.cache

import com.bara.auth.config.RefreshTokenProperties
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RefreshTokenRedisStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val template = mockk<StringRedisTemplate> {
        every { opsForValue() } returns valueOps
        every { delete(any<String>()) } returns true
    }
    private val props = RefreshTokenProperties(
        audience = "bara-refresh",
        expirySeconds = 604800,
        gracePeriodSeconds = 30,
    )
    private val store = RefreshTokenRedisStore(template, props)

    @Test
    fun `save는 userId 키로 jti와 family를 저장한다`() {
        store.save("user-1", "jti-abc", "family-xyz")

        verify {
            valueOps.set(
                "refresh:user-1",
                match { it.contains("jti-abc") && it.contains("family-xyz") },
                Duration.ofSeconds(604800),
            )
        }
    }

    @Test
    fun `find는 저장된 토큰 정보를 반환한다`() {
        every { valueOps.get("refresh:user-1") } returns """{"jti":"jti-abc","family":"family-xyz"}"""

        val result = store.find("user-1")

        assertNotNull(result)
        assertEquals("jti-abc", result!!.jti)
        assertEquals("family-xyz", result.family)
    }

    @Test
    fun `find는 저장된 값이 없으면 null을 반환한다`() {
        every { valueOps.get("refresh:user-1") } returns null

        assertNull(store.find("user-1"))
    }

    @Test
    fun `delete는 userId 키를 삭제한다`() {
        store.delete("user-1")

        verify { template.delete("refresh:user-1") }
    }

    @Test
    fun `saveGrace는 이전 jti를 grace period TTL로 저장한다`() {
        store.saveGrace("old-jti")

        verify {
            valueOps.set("refresh:grace:old-jti", "valid", Duration.ofSeconds(30))
        }
    }

    @Test
    fun `isGraceValid는 grace 키가 있으면 true를 반환한다`() {
        every { template.hasKey("refresh:grace:old-jti") } returns true

        assertTrue(store.isGraceValid("old-jti"))
    }

    @Test
    fun `isGraceValid는 grace 키가 없으면 false를 반환한다`() {
        every { template.hasKey("refresh:grace:old-jti") } returns false

        assertFalse(store.isGraceValid("old-jti"))
    }
}
