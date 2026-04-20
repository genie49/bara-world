package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Redis Stream 단일 구독자 polling 구현.
 *
 * 동작:
 *   1. 생성 직후 fromStreamId 로 backfill (XREAD COUNT 1000 STREAMS key fromId)
 *   2. 리스너에 각 이벤트 전달
 *   3. lastStreamId 갱신
 *   4. 200ms 간격으로 다음 엔트리 polling
 *   5. close() 호출 시 스케줄 cancel
 */
class EventBusPoller(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val streamKey: String,
    fromStreamId: String,
    private val listener: (entryId: String, event: TaskEvent) -> Unit,
    private val executor: ScheduledExecutorService,
    private val pollIntervalMs: Long = 200,
) : Subscription {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val lastId = AtomicReference(fromStreamId)
    private val scheduled: ScheduledFuture<*>
    @Volatile private var closed = false

    init {
        scheduled = executor.scheduleWithFixedDelay(
            { pollOnce() },
            0, pollIntervalMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun pollOnce() {
        if (closed) return
        try {
            val offset = StreamOffset.create(streamKey, ReadOffset.from(lastId.get()))
            val opts = StreamReadOptions.empty().count(1000)
            val records = redisTemplate.opsForStream<Any, Any>()
                .read(opts, offset) ?: return
            for (record in records) {
                val json = record.value["event"]?.toString() ?: continue
                val event = TaskEventJson.deserialize(objectMapper, json)
                val id = record.id.toString()
                try {
                    listener(id, event)
                } catch (e: Throwable) {
                    logger.error("Subscriber listener threw for stream={} id={}", streamKey, record.id, e)
                }
                lastId.set(id)
            }
        } catch (e: Throwable) {
            logger.warn("Poll error stream={}: {}", streamKey, e.message)
        }
    }

    override fun close() {
        closed = true
        scheduled.cancel(false)
    }

    companion object {
        fun newExecutor(): ScheduledExecutorService =
            Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                { r -> Thread(r, "event-bus-poller").apply { isDaemon = true } },
            )
    }
}
