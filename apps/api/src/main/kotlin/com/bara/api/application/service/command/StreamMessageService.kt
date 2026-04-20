package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.command.StreamMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class StreamMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val sseBridge: SseBridge,
    private val properties: TaskProperties,
) : StreamMessageUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun stream(
        userId: String,
        agentName: String,
        envelopeId: JsonNode?,
        request: SendMessageUseCase.SendMessageRequest,
    ): SseEmitter {
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

        // ② EventBus publish submitted — 구독 이전에 발행되어도 fromStreamId="0" backfill 로 수신된다.
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        // ③ Stream 구독 등록 (fromStreamId="0" → backfill + tailing)
        val emitter = SseEmitter(properties.emitterTimeoutMs)
        val subscription = taskEventBusPort.subscribe(taskId, "0") { event ->
            val dto = A2ATaskMapper.fromEvent(task, event)
            // TODO(Task 9): propagate real Redis Stream entry id once listener signature is extended.
            sseBridge.send(taskId, "0", dto, event.final)
        }

        // ④ SSE 생명주기 등록 (emitter 종료 시 subscription 도 close)
        sseBridge.attach(taskId, envelopeId, emitter, subscription)

        // ⑤ Kafka publish. 실패 시 즉시 failed 전환 + 최종 이벤트 발행 + 예외 전파.
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
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패 (stream)")
            throw e
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("request_id", requestId)
        WideEvent.put("outcome", "stream_started")
        WideEvent.message("태스크 스트리밍 시작")
        logger.debug("Stream started taskId={}", taskId)

        return emitter
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
}
