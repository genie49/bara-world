package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.config.TaskProperties
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SseHeartbeatSchedulerTest {

    @Test
    fun `scheduler invokes bridge heartbeat at configured interval`() {
        val bridge = mockk<SseBridge>(relaxed = true)
        val props = TaskProperties(heartbeatIntervalMs = 100)
        val executor = Executors.newSingleThreadScheduledExecutor()

        val scheduler = SseHeartbeatScheduler(bridge, props, executor)
        scheduler.start()

        // 350ms 안에 최소 2번은 불린다 (100ms 간격, initial delay 100ms)
        executor.schedule({}, 350, TimeUnit.MILLISECONDS).get()
        scheduler.stop()

        verify(atLeast = 2) { bridge.heartbeat() }
        executor.shutdownNow()
    }

    @Test
    fun `scheduler keeps running when bridge throws`() {
        val bridge = mockk<SseBridge>()
        every { bridge.heartbeat() } throws RuntimeException("boom") andThenJust Runs
        val props = TaskProperties(heartbeatIntervalMs = 80)
        val executor = Executors.newSingleThreadScheduledExecutor()

        val scheduler = SseHeartbeatScheduler(bridge, props, executor)
        scheduler.start()
        executor.schedule({}, 300, TimeUnit.MILLISECONDS).get()
        scheduler.stop()

        verify(atLeast = 2) { bridge.heartbeat() }
        executor.shutdownNow()
    }
}
