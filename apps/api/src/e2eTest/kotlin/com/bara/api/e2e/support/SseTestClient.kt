package com.bara.api.e2e.support

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SseTestClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : AutoCloseable {

    data class Received(val id: String?, val event: String?, val data: String)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingQueue<Received>()
    private val closeFuture = CompletableFuture<Throwable?>()
    private var source: EventSource? = null

    private val listener = object : EventSourceListener() {
        override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
            queue.offer(Received(id, type, data))
        }
        override fun onClosed(es: EventSource) {
            closeFuture.complete(null)
        }
        override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
            closeFuture.complete(t ?: RuntimeException("SSE failure status=${response?.code}"))
        }
    }

    /** GET 으로 SSE 스트림 열기. */
    fun openGet() {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
    }

    /** POST body 로 SSE 스트림 열기 (message:stream 용). */
    fun openPost(body: String, contentType: String = "application/json") {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
    }

    /** 다음 이벤트 대기. */
    fun nextEvent(timeoutMs: Long = 10_000): Received? =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** 스트림 종료 대기. */
    fun waitForClose(timeoutMs: Long = 30_000): Throwable? =
        closeFuture.get(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        source?.cancel()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
