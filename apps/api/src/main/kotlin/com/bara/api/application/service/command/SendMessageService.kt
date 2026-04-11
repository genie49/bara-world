package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

@Service
class SendMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val properties: TaskProperties,
) : SendMessageUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): CompletableFuture<A2ATaskDto> {
        val agentId = agentRegistryPort.getAgentId(agentName)
            ?: throw AgentUnavailableException()

        val now = Instant.now()
        val taskId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val contextId = request.contextId ?: UUID.randomUUID().toString()
        val expiredAt = now.plus(Duration.ofDays(properties.mongoTtlDays))

        val inputMessage = A2AMessage(
            messageId = request.messageId,
            role = "user",
            parts = listOf(A2APart(kind = "text", text = request.text)),
        )

        // ① Mongo insert submitted
        val task = Task(
            id = taskId,
            agentId = agentId,
            agentName = agentName,
            userId = userId,
            contextId = contextId,
            state = TaskState.SUBMITTED,
            inputMessage = inputMessage,
            requestId = requestId,
            createdAt = now,
            updatedAt = now,
            expiredAt = expiredAt,
        )
        taskRepositoryPort.save(task)

        // ② EventBus publish submitted — 블로킹 await 등록 전이어도 backfill로 수신되지만 일관성 위해 먼저.
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        // ③ 블로킹 await 등록
        val future = taskEventBusPort.await(
            taskId = taskId,
            timeout = Duration.ofSeconds(properties.blockTimeoutSeconds),
        )

        // ④ Kafka publish (ack 대기). 실패 시 즉시 failed 전환.
        try {
            taskPublisherPort.publish(
                agentId = agentId,
                payload = TaskMessagePayload(
                    taskId = taskId,
                    contextId = contextId,
                    userId = userId,
                    requestId = requestId,
                    resultTopic = "results.api",
                    allowedAgents = emptyList(),
                    message = inputMessage,
                ),
            )
        } catch (e: KafkaPublishException) {
            markFailed(task, "kafka-publish-failed", e.message ?: "Kafka publish failed")
            future.cancel(true)
            WideEvent.put("task_id", taskId)
            WideEvent.put("agent_name", agentName)
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패")
            throw e
        }

        logBlockingStart(taskId, agentName, agentId, userId)

        return future.handle { event, throwable ->
            when {
                throwable != null -> handleAwaitFailure(task, throwable)
                event != null -> {
                    WideEvent.put("task_id", taskId)
                    WideEvent.put("outcome", "task_${event.state.name.lowercase()}")
                    WideEvent.message("태스크 블로킹 완료")
                    A2ATaskMapper.fromEvent(task, event)
                }
                else -> error("unreachable")
            }
        }
    }

    override fun sendAsync(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): A2ATaskDto {
        val agentId = agentRegistryPort.getAgentId(agentName)
            ?: throw AgentUnavailableException()

        val now = Instant.now()
        val taskId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val contextId = request.contextId ?: UUID.randomUUID().toString()
        val expiredAt = now.plus(Duration.ofDays(properties.mongoTtlDays))

        val inputMessage = A2AMessage(
            messageId = request.messageId,
            role = "user",
            parts = listOf(A2APart(kind = "text", text = request.text)),
        )

        val task = Task(
            id = taskId,
            agentId = agentId,
            agentName = agentName,
            userId = userId,
            contextId = contextId,
            state = TaskState.SUBMITTED,
            inputMessage = inputMessage,
            requestId = requestId,
            createdAt = now,
            updatedAt = now,
            expiredAt = expiredAt,
        )
        taskRepositoryPort.save(task)
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        try {
            taskPublisherPort.publish(
                agentId = agentId,
                payload = TaskMessagePayload(
                    taskId = taskId,
                    contextId = contextId,
                    userId = userId,
                    requestId = requestId,
                    resultTopic = "results.api",
                    allowedAgents = emptyList(),
                    message = inputMessage,
                ),
            )
        } catch (e: KafkaPublishException) {
            markFailed(task, "kafka-publish-failed", e.message ?: "Kafka publish failed")
            WideEvent.put("task_id", taskId)
            WideEvent.put("agent_name", agentName)
            WideEvent.put("user_id", userId)
            WideEvent.put("return_immediately", true)
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패 (async)")
            throw e
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("return_immediately", true)
        WideEvent.put("outcome", "task_submitted")
        WideEvent.message("태스크 async 제출 완료")

        return A2ATaskMapper.toDto(task)
    }

    private fun handleAwaitFailure(task: Task, throwable: Throwable): Nothing {
        val cause = (throwable as? java.util.concurrent.CompletionException)?.cause ?: throwable
        if (cause is TimeoutException) {
            WideEvent.put("task_id", task.id)
            WideEvent.put("outcome", "agent_timeout")
            WideEvent.message("Agent 응답 타임아웃")
            throw AgentTimeoutException("Agent did not respond within ${properties.blockTimeoutSeconds}s")
        }
        WideEvent.put("task_id", task.id)
        WideEvent.put("outcome", "task_failed")
        WideEvent.message("태스크 대기 중 오류: ${cause.message}")
        throw cause
    }

    private fun markFailed(task: Task, errorCode: String, errorMessage: String) {
        val now = Instant.now()
        val expiredAt = now.plus(Duration.ofDays(properties.mongoTtlDays))
        taskRepositoryPort.updateState(
            id = task.id,
            state = TaskState.FAILED,
            statusMessage = null,
            artifacts = emptyList(),
            errorCode = errorCode,
            errorMessage = errorMessage,
            updatedAt = now,
            completedAt = now,
            expiredAt = expiredAt,
        )
        taskEventBusPort.publish(
            task.id,
            TaskEvent(
                taskId = task.id,
                contextId = task.contextId,
                state = TaskState.FAILED,
                statusMessage = null,
                errorCode = errorCode,
                errorMessage = errorMessage,
                final = true,
                timestamp = now,
            ),
        )
    }

    private fun logBlockingStart(taskId: String, agentName: String, agentId: String, userId: String) {
        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        logger.debug("Blocking send started taskId={}", taskId)
    }
}
