package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.application.port.out.Subscription
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared lifecycle manager for SSE emitters + event-bus subscriptions.
 *
 * Used by `StreamMessageService` (scenario C) and `SubscribeTaskService` (scenario D) to
 *   1) register an emitter paired with a [Subscription] returned by `TaskEventBusPort.subscribe()`,
 *   2) push each [com.bara.api.domain.model.TaskEvent] — serialized as a JSON-RPC response envelope
 *      wrapping [A2ATaskDto] — as `event: message` / `id: <entryId>` frames,
 *   3) release both sides deterministically when the stream terminates (final=true, client disconnect,
 *      emitter timeout, or I/O error), and
 *   4) support [heartbeat] broadcasts (Task 4) so idle clients don't time out.
 *
 * Thread safety: [active] is a [ConcurrentHashMap]; release is idempotent (`remove(...)?.also`).
 */
@Component
class SseBridge(
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<String, Entry>()

    fun attach(taskId: String, envelopeId: JsonNode?, emitter: SseEmitter, subscription: Subscription) {
        val entry = Entry(emitter, subscription, envelopeId)
        active[taskId] = entry
        emitter.onCompletion { release(taskId, "completion") }
        emitter.onTimeout {
            logger.debug("SSE emitter timeout taskId={}", taskId)
            emitter.complete()
            release(taskId, "timeout")
        }
        emitter.onError { e ->
            logger.debug("SSE emitter error taskId={}: {}", taskId, e.message)
            release(taskId, "error")
        }
    }

    fun send(taskId: String, entryId: String, dto: A2ATaskDto, final: Boolean) {
        val entry = active[taskId] ?: return
        val envelope = JsonRpcResponse(id = entry.envelopeId, result = dto)
        try {
            val event = SseEmitter.event()
                .id(entryId)
                .name("message")
                .data(objectMapper.writeValueAsString(envelope))
            entry.emitter.send(event)
            if (final) {
                entry.emitter.complete()
                release(taskId, "final")
            }
        } catch (e: IOException) {
            logger.debug("SSE send failed taskId={}: {}", taskId, e.message)
            release(taskId, "io-error")
        } catch (e: IllegalStateException) {
            logger.debug("SSE emitter already closed taskId={}: {}", taskId, e.message)
            release(taskId, "emitter-closed")
        }
    }

    fun heartbeat() {
        active.forEach { (taskId, entry) ->
            try {
                entry.emitter.send(SseEmitter.event().comment("keepalive"))
            } catch (e: IOException) {
                logger.debug("heartbeat failed taskId={}: {}", taskId, e.message)
                release(taskId, "heartbeat-fail")
            } catch (e: IllegalStateException) {
                logger.debug("heartbeat on closed emitter taskId={}: {}", taskId, e.message)
                release(taskId, "heartbeat-fail")
            }
        }
    }

    fun activeCount(): Int = active.size

    private fun release(taskId: String, reason: String) {
        active.remove(taskId)?.also {
            try {
                it.subscription.close()
            } catch (_: Exception) {
                // Subscription.close() must not propagate cleanup errors.
            }
        }
        logger.trace("SSE released taskId={} reason={}", taskId, reason)
    }

    private data class Entry(
        val emitter: SseEmitter,
        val subscription: Subscription,
        val envelopeId: JsonNode?,
    )
}
