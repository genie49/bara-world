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
    fun `streamExists 는 hasKey 결과를 그대로 반환한다`() {
        every { redisTemplate.hasKey("stream:task:t-1") } returns true
        every { redisTemplate.hasKey("stream:task:t-gone") } returns false

        val bus = newBus()

        assertEquals(true, bus.streamExists("t-1"))
        assertEquals(false, bus.streamExists("t-gone"))
    }

    @Test
    fun `close는 stream task 키에 grace period로 expire 를 건다`() {
        every { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) } returns true

        newBus().close("t-1")

        verify { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) }
    }

    @Test
    fun `await는 backfill 된 final 이벤트로 즉시 complete 된다`() {
        val streamOpsMock = streamOps
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOpsMock

        every {
            streamOpsMock.read(any<org.springframework.data.redis.connection.stream.StreamReadOptions>(),
                any<org.springframework.data.redis.connection.stream.StreamOffset<String>>())
        } returns listOf(
            org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                .`in`("stream:task:t-1")
                .ofMap(mapOf<Any, Any>(
                    "event" to """{"taskId":"t-1","contextId":"c-1","state":"completed","final":true,"timestamp":"2026-04-11T00:00:00Z"}""",
                ))
                .withId(org.springframework.data.redis.connection.stream.RecordId.of(1L, 0L))
        )

        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            val bus = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties, executor)
            val future = bus.await("t-1", Duration.ofSeconds(2))
            val event = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(TaskState.COMPLETED, event.state)
            assertEquals(true, event.final)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `await는 timeout 시 TimeoutException 을 반환한다`() {
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOps
        every {
            streamOps.read(any<org.springframework.data.redis.connection.stream.StreamReadOptions>(),
                any<org.springframework.data.redis.connection.stream.StreamOffset<String>>())
        } returns emptyList()

        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            val bus = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties, executor)
            val future = bus.await("t-1", Duration.ofMillis(300))
            val ex = kotlin.runCatching { future.get(1, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
            assertEquals(java.util.concurrent.TimeoutException::class, ex?.cause?.let { it::class })
        } finally {
            executor.shutdownNow()
        }
    }
}
