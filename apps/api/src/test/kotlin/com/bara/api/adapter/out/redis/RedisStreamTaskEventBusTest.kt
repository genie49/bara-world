package com.bara.api.adapter.out.redis

import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class RedisStreamTaskEventBusTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val streamOps = mockk<StreamOperations<String, Any, Any>>()
    private val properties = TaskProperties()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private fun newBus() = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties)

    @Test
    fun `publish는 stream task 키에 XADD 한다`() {
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOps
        every { streamOps.add(any<MapRecord<String, Any, Any>>()) } returns
            RecordId.of(1712665200000L, 0L)

        val event = TaskEvent(
            taskId = "t-1",
            contextId = "c-1",
            state = TaskState.SUBMITTED,
            statusMessage = null,
            final = false,
            timestamp = Instant.parse("2026-04-11T00:00:00Z"),
        )

        val id = newBus().publish("t-1", event)

        assertEquals("1712665200000-0", id)
        verify {
            streamOps.add(match<MapRecord<String, Any, Any>> { rec ->
                rec.stream == "stream:task:t-1" && rec.value.containsKey("event")
            })
        }
    }

    @Test
    fun `close는 stream task 키에 grace period로 expire 를 건다`() {
        every { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) } returns true

        newBus().close("t-1")

        verify { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) }
    }
}
