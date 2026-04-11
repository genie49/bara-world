package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class ResultConsumerAdapter(
    private val repository: TaskRepositoryPort,
    private val eventBus: TaskEventBusPort,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [RESULT_TOPIC],
        containerFactory = "resultKafkaListenerContainerFactory",
    )
    fun onMessage(payload: String, ack: Acknowledgment) {
        try {
            val wire = parsePayload(payload) ?: run {
                logger.warn("Failed to parse result payload — skip")
                ack.acknowledge()
                return
            }

            val taskId = wire.taskId
            val existing = repository.findById(taskId)
            if (existing == null) {
                logger.warn("Task not found for result taskId={} — skip", taskId)
                WideEvent.put("task_id", taskId)
                WideEvent.put("outcome", "result_skipped_unknown")
                ack.acknowledge()
                return
            }

            val (state, errorCode, errorMessage) = translateState(wire)
            val now = Instant.now()
            val completedAt = if (state.isTerminal) now else null
            val expiredAt = if (state.isTerminal) {
                now.plus(Duration.ofDays(properties.mongoTtlDays))
            } else null

            val artifacts = wire.artifact?.let { listOf(it) } ?: emptyList()

            repository.updateState(
                id = taskId,
                state = state,
                statusMessage = wire.statusMessage,
                artifacts = artifacts,
                errorCode = errorCode,
                errorMessage = errorMessage,
                updatedAt = now,
                completedAt = completedAt,
                expiredAt = expiredAt,
            )

            val event = TaskEvent(
                taskId = taskId,
                contextId = wire.contextId,
                state = state,
                statusMessage = wire.statusMessage,
                artifact = wire.artifact,
                errorCode = errorCode,
                errorMessage = errorMessage,
                final = state.isTerminal,
                timestamp = now,
            )
            eventBus.publish(taskId, event)
            if (state.isTerminal) {
                eventBus.close(taskId)
            }

            WideEvent.put("task_id", taskId)
            WideEvent.put("state", state.name.lowercase())
            WideEvent.put("outcome", if (state.isTerminal) "result_processed" else "result_processed_intermediate")
            WideEvent.message("태스크 결과 처리 완료")
            ack.acknowledge()
        } catch (e: Throwable) {
            logger.error("ResultConsumer error — message will be re-delivered", e)
            throw e
        }
    }

    private fun parsePayload(payload: String): WireResult? =
        runCatching { objectMapper.readValue(payload, WireResult::class.java) }
            .getOrNull()

    private fun translateState(wire: WireResult): Triple<TaskState, String?, String?> {
        val raw = wire.state.lowercase()
        return when (raw) {
            "submitted" -> Triple(TaskState.SUBMITTED, null, null)
            "working" -> Triple(TaskState.WORKING, null, null)
            "completed" -> Triple(TaskState.COMPLETED, null, null)
            "failed" -> Triple(TaskState.FAILED, wire.errorCode, wire.errorMessage)
            "canceled" -> Triple(TaskState.CANCELED, null, null)
            "rejected" -> Triple(TaskState.REJECTED, wire.errorCode ?: "rejected", wire.errorMessage)
            "input-required", "auth-required" ->
                Triple(TaskState.FAILED, "unsupported-state", "Agent returned unsupported state=$raw")
            else ->
                Triple(TaskState.FAILED, "unsupported-state", "Unknown state=$raw")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WireResult(
        val taskId: String,
        val contextId: String,
        val state: String,
        val statusMessage: A2AMessage? = null,
        val artifact: A2AArtifact? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val final: Boolean = false,
        val timestamp: String? = null,
    )

    companion object {
        const val RESULT_TOPIC = "results.api"
    }
}
