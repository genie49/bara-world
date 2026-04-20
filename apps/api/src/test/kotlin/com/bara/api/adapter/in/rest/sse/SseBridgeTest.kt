package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.adapter.`in`.rest.a2a.A2AMessageDto
import com.bara.api.adapter.`in`.rest.a2a.A2APartDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskStatusDto
import com.bara.api.application.port.out.Subscription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseBridgeTest {

    private val objectMapper = ObjectMapper()

    private fun bridge() = SseBridge(objectMapper)

    private fun sampleTask(id: String = "task-1"): A2ATaskDto =
        A2ATaskDto(
            id = id,
            contextId = "ctx-1",
            status = A2ATaskStatusDto(
                state = "working",
                message = A2AMessageDto(
                    messageId = "msg-1",
                    role = "agent",
                    parts = listOf(A2APartDto(kind = "text", text = "hello")),
                ),
            ),
        )

    @Test
    fun `attach registers emitter and non-final send does not complete`() {
        val bridge = bridge()
        val emitter = SseEmitter()
        val subscription = mockk<Subscription>(relaxed = true)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        emitter.onCompletion { completed.set(true) }

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        bridge.send("task-1", "0-1", sampleTask(), final = false)

        assertEquals(1, bridge.activeCount())
        assertTrue(!completed.get(), "non-final send must not complete emitter")
        verify(exactly = 0) { subscription.close() }
    }

    @Test
    fun `send final completes emitter closes subscription and removes entry`() {
        val bridge = bridge()
        val emitter = SseEmitter()
        val subscription = mockk<Subscription>(relaxed = true)

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        bridge.send("task-1", "0-2", sampleTask(), final = true)

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }
    }

    @Test
    fun `attach registers onError callback that releases subscription`() {
        val bridge = bridge()
        val emitter = spyk(SseEmitter())
        val subscription = mockk<Subscription>(relaxed = true)

        // Capture the onError lambda SseBridge registers.
        val errorSlot = slot<java.util.function.Consumer<Throwable>>()
        every { emitter.onError(capture(errorSlot)) } answers { callOriginal() }

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        // Simulate Spring invoking the registered error callback (client disconnect / IO error).
        errorSlot.captured.accept(RuntimeException("boom"))

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }
    }

    @Test
    fun `heartbeat sends keepalive to active emitter without error`() {
        val bridge = bridge()
        val emitter = SseEmitter()
        val subscription = mockk<Subscription>(relaxed = true)

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)

        bridge.heartbeat()

        assertEquals(1, bridge.activeCount())
        assertTrue(bridge.activeCount() > 0, "healthy heartbeat must keep entry active")
        verify(exactly = 0) { subscription.close() }
    }

    @Test
    fun `send IOException releases entry and closes subscription`() {
        val bridge = bridge()
        val emitter = spyk(SseEmitter())
        val subscription = mockk<Subscription>(relaxed = true)

        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws java.io.IOException("client gone")

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        bridge.send("task-1", "1-0", sampleTask(), final = false)

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }
    }

    @Test
    fun `send IllegalStateException releases entry`() {
        val bridge = bridge()
        val emitter = spyk(SseEmitter())
        val subscription = mockk<Subscription>(relaxed = true)

        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws IllegalStateException("emitter already completed")

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        bridge.send("task-1", "1-0", sampleTask(), final = false)

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }
    }

    @Test
    fun `heartbeat failure releases broken entry`() {
        val bridge = bridge()
        val emitter = spyk(SseEmitter())
        val subscription = mockk<Subscription>(relaxed = true)

        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } throws java.io.IOException("client gone")

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        bridge.heartbeat()

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }
    }

    @Test
    fun `attach registers onCompletion callback that releases subscription`() {
        val bridge = bridge()
        val emitter = spyk(SseEmitter())
        val subscription = mockk<Subscription>(relaxed = true)

        val completionSlot = slot<Runnable>()
        every { emitter.onCompletion(capture(completionSlot)) } answers { callOriginal() }

        bridge.attach("task-1", TextNode("env-1"), emitter, subscription)
        assertEquals(1, bridge.activeCount())

        // Simulate Spring invoking the registered completion callback (e.g. client disconnect).
        completionSlot.captured.run()

        assertEquals(0, bridge.activeCount())
        verify(exactly = 1) { subscription.close() }

        // Subsequent send must be a no-op (entry removed).
        bridge.send("task-1", "0-3", sampleTask(), final = false)
        assertEquals(0, bridge.activeCount())
    }
}
