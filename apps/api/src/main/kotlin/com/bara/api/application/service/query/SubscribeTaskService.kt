package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.query.SubscribeTaskQuery
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class SubscribeTaskService(
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val sseBridge: SseBridge,
    private val properties: TaskProperties,
) : SubscribeTaskQuery {

    override fun subscribe(userId: String, taskId: String, lastEventId: String?): SseEmitter {
        val task = taskRepositoryPort.findById(taskId) ?: run {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_not_found")
            WideEvent.message("Task 구독 실패 - 존재하지 않음")
            throw TaskNotFoundException(taskId)
        }

        if (task.userId != userId) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_access_denied")
            WideEvent.message("Task 구독 실패 - 권한 없음")
            throw TaskAccessDeniedException(taskId)
        }

        if (task.state.isTerminal && !taskEventBusPort.streamExists(taskId)) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("last_event_id", lastEventId ?: "null")
            WideEvent.put("outcome", "stream_expired")
            WideEvent.message("스트림 만료 — 폴링으로 전환")
            throw StreamUnsupportedException("Stream for task $taskId has expired")
        }

        val fromId = lastEventId ?: "0"
        val emitter = SseEmitter(properties.emitterTimeoutMs)
        val subscription = taskEventBusPort.subscribe(taskId, fromId) { entryId, event ->
            val dto = A2ATaskMapper.fromEvent(task, event)
            sseBridge.send(taskId, entryId, dto, event.final)
        }
        sseBridge.attach(taskId, envelopeId = null, emitter = emitter, subscription = subscription)

        WideEvent.put("task_id", taskId)
        WideEvent.put("user_id", userId)
        WideEvent.put("last_event_id", lastEventId ?: "null")
        WideEvent.put("outcome", "stream_resubscribed")
        WideEvent.message("스트림 재연결")

        return emitter
    }
}
