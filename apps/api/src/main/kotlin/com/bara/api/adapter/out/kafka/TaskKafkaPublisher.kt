package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.KafkaPublishException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class TaskKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : TaskPublisherPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(agentId: String, payload: TaskMessagePayload) {
        val topic = "tasks.$agentId"
        val wire = mapOf(
            "task_id" to payload.taskId,
            "context_id" to payload.contextId,
            "user_id" to payload.userId,
            "request_id" to payload.requestId,
            "result_topic" to payload.resultTopic,
            "allowed_agents" to payload.allowedAgents,
            "message" to mapOf(
                "message_id" to payload.message.messageId,
                "role" to payload.message.role,
                "parts" to payload.message.parts.map {
                    mapOf("kind" to it.kind, "text" to it.text)
                },
            ),
        )
        val json = objectMapper.writeValueAsString(wire)
        try {
            kafkaTemplate.send(topic, json).get(KAFKA_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info("Task published topic={} taskId={}", topic, payload.taskId)
        } catch (e: TimeoutException) {
            throw KafkaPublishException("Kafka ack timeout topic=$topic", e)
        } catch (e: ExecutionException) {
            throw KafkaPublishException("Kafka publish failed topic=$topic", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KafkaPublishException("Kafka publish interrupted topic=$topic", e)
        }
    }

    companion object {
        private const val KAFKA_ACK_TIMEOUT_SECONDS = 5L
    }
}
