package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Component
class RedisStreamTaskEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
) : TaskEventBusPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(taskId: String, event: TaskEvent): String {
        val key = streamKey(taskId)
        val json = TaskEventJson.serialize(objectMapper, event)
        val record = StreamRecords.newRecord()
            .`in`(key)
            .ofMap(mapOf<Any, Any>("event" to json))
        val recordId = redisTemplate.opsForStream<Any, Any>().add(record)
            ?: error("Redis XADD returned null recordId for key=$key")
        return recordId.toString()
    }

    override fun subscribe(
        taskId: String,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription {
        TODO("Task 8에서 구현 — pump thread + fan-out")
    }

    override fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent> {
        TODO("Task 8에서 구현")
    }

    override fun close(taskId: String) {
        val key = streamKey(taskId)
        val grace = Duration.ofSeconds(properties.streamGracePeriodSeconds)
        redisTemplate.expire(key, grace)
        logger.debug("Scheduled stream close key={} after={}s", key, grace.seconds)
    }

    private fun streamKey(taskId: String): String = "stream:task:$taskId"
}
