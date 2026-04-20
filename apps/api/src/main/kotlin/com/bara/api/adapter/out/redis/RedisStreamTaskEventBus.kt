package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class RedisStreamTaskEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
    private val executor: ScheduledExecutorService = EventBusPoller.newExecutor(),
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
    ): Subscription = EventBusPoller(
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        streamKey = streamKey(taskId),
        fromStreamId = fromStreamId,
        listener = listener,
        executor = executor,
    )

    override fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent> {
        val future = CompletableFuture<TaskEvent>()
        val subscription = subscribe(taskId, "0") { event ->
            if (event.final && !future.isDone) {
                future.complete(event)
            }
        }
        val timeoutFuture = executor.schedule({
            if (!future.isDone) {
                future.completeExceptionally(TimeoutException("await timeout taskId=$taskId"))
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS)

        future.whenComplete { _, _ ->
            subscription.close()
            timeoutFuture.cancel(false)
        }
        return future
    }

    override fun close(taskId: String) {
        val key = streamKey(taskId)
        val grace = Duration.ofSeconds(properties.streamGracePeriodSeconds)
        redisTemplate.expire(key, grace)
        logger.debug("Scheduled stream close key={} after={}s", key, grace.seconds)
    }

    override fun streamExists(taskId: String): Boolean =
        redisTemplate.hasKey(streamKey(taskId))

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }

    private fun streamKey(taskId: String): String = "stream:task:$taskId"
}
