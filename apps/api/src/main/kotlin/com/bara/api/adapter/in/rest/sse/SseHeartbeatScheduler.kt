package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.config.TaskProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Periodically invokes [SseBridge.heartbeat] so idle SSE connections don't get dropped by
 * upstream proxies (Cloudflare / Traefik typically time out idle streams around 60s).
 *
 * The scheduler runs on a single-threaded daemon executor by default; the executor is
 * constructor-injectable so tests can supply their own (and drive timing deterministically).
 *
 * Exceptions thrown by [SseBridge.heartbeat] are swallowed via [runCatching] to prevent
 * [java.util.concurrent.ScheduledExecutorService.scheduleAtFixedRate] from cancelling the
 * schedule on a single failure.
 */
@Component
class SseHeartbeatScheduler(
    private val bridge: SseBridge,
    private val properties: TaskProperties,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sse-heartbeat").apply { isDaemon = true }
    },
) {
    @Volatile
    private var scheduled: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        val interval = properties.heartbeatIntervalMs
        scheduled = executor.scheduleAtFixedRate(
            { runCatching { bridge.heartbeat() } },
            interval,
            interval,
            TimeUnit.MILLISECONDS,
        )
    }

    @PreDestroy
    fun stop() {
        scheduled?.cancel(false)
        scheduled = null
    }
}
