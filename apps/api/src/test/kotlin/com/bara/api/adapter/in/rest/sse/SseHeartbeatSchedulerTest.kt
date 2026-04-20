package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.config.TaskProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SseHeartbeatSchedulerTest {

    @Test
    fun `scheduler invokes bridge heartbeat at configured interval`() {
        val latch = CountDownLatch(3)
        val bridge = mockk<SseBridge> {
            every { heartbeat() } answers { latch.countDown() }
        }
        val props = TaskProperties(heartbeatIntervalMs = 50)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val scheduler = SseHeartbeatScheduler(bridge, props, executor)

        scheduler.start()
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS))
                .`as`("heartbeat should fire at least 3 times")
                .isTrue
        } finally {
            scheduler.stop()
            executor.shutdownNow()
        }

        verify(atLeast = 3) { bridge.heartbeat() }
    }

    @Test
    fun `scheduler keeps running when bridge heartbeat throws`() {
        val totalCalls = CountDownLatch(3)
        val firstCall = CountDownLatch(1)
        val bridge = mockk<SseBridge> {
            every { heartbeat() } answers {
                totalCalls.countDown()
                if (firstCall.count > 0) {
                    firstCall.countDown()
                    throw RuntimeException("boom")
                }
            }
        }
        val props = TaskProperties(heartbeatIntervalMs = 50)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val scheduler = SseHeartbeatScheduler(bridge, props, executor)

        scheduler.start()
        try {
            assertThat(totalCalls.await(3, TimeUnit.SECONDS))
                .`as`("scheduler should continue after thrown exception")
                .isTrue
        } finally {
            scheduler.stop()
            executor.shutdownNow()
        }

        assertThat(firstCall.count).isEqualTo(0L) // exception path exercised
        verify(atLeast = 3) { bridge.heartbeat() }
    }
}
