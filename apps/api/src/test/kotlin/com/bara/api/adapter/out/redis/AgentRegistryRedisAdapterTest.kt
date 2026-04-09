package com.bara.api.adapter.out.redis

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRegistryRedisAdapterTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val adapter = AgentRegistryRedisAdapter(redisTemplate)

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `register는 60초 TTL로 Redis에 저장한다`() {
        justRun { valueOps.set("agent:registry:my-agent", "agent-001", Duration.ofSeconds(60)) }

        adapter.register("my-agent", "agent-001")

        verify { valueOps.set("agent:registry:my-agent", "agent-001", Duration.ofSeconds(60)) }
    }

    @Test
    fun `isRegistered는 키가 있으면 true를 반환한다`() {
        every { redisTemplate.hasKey("agent:registry:my-agent") } returns true

        assertTrue(adapter.isRegistered("my-agent"))
    }

    @Test
    fun `isRegistered는 키가 없으면 false를 반환한다`() {
        every { redisTemplate.hasKey("agent:registry:unknown") } returns false

        assertFalse(adapter.isRegistered("unknown"))
    }

    @Test
    fun `getAgentId는 저장된 값을 반환한다`() {
        every { valueOps.get("agent:registry:my-agent") } returns "agent-001"

        assertEquals("agent-001", adapter.getAgentId("my-agent"))
    }

    @Test
    fun `getAgentId는 키가 없으면 null을 반환한다`() {
        every { valueOps.get("agent:registry:unknown") } returns null

        assertNull(adapter.getAgentId("unknown"))
    }

    @Test
    fun `refreshTtl은 TTL을 60초로 갱신한다`() {
        every { redisTemplate.expire("agent:registry:my-agent", Duration.ofSeconds(60)) } returns true

        adapter.refreshTtl("my-agent")

        verify { redisTemplate.expire("agent:registry:my-agent", Duration.ofSeconds(60)) }
    }
}
