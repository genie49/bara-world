package com.bara.auth.adapter.out.external

import com.bara.auth.domain.exception.InvalidOAuthStateException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisOAuthStateStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val template = mockk<StringRedisTemplate> {
        every { opsForValue() } returns valueOps
    }
    private val store = RedisOAuthStateStore(template)

    @Test
    fun `issue는 새로운 state를 생성하고 Redis에 TTL과 함께 저장한다`() {
        val state = store.issue()
        assertTrue(state.isNotBlank())
        verify { valueOps.set("oauth:state:$state", "", Duration.ofSeconds(300)) }
    }

    @Test
    fun `consume은 존재하는 state를 조회 후 삭제한다`() {
        every { template.hasKey("oauth:state:abc") } returns true
        every { template.delete("oauth:state:abc") } returns true
        store.consume("abc")
        verify { template.delete("oauth:state:abc") }
    }

    @Test
    fun `consume은 존재하지 않는 state에 대해 InvalidOAuthStateException을 던진다`() {
        every { template.hasKey("oauth:state:missing") } returns false
        assertThrows(InvalidOAuthStateException::class.java) { store.consume("missing") }
    }
}
