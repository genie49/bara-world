package com.bara.api.application.port.out

import com.bara.api.domain.model.TaskEvent
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface TaskEventBusPort {

    /** 이벤트 발행. 반환: 스트림 entry ID (Redis Stream `ms-seq`). */
    fun publish(taskId: String, event: TaskEvent): String

    /**
     * 스트림 구독.
     * fromStreamId:
     *   "$"        — 새 이벤트만
     *   "0"        — 처음부터 (backfill + tailing)
     *   "<id>"     — 특정 offset 이후 (Last-Event-ID 재연결)
     * listener는 백그라운드 스레드에서 호출된다.
     */
    fun subscribe(
        taskId: String,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription

    /**
     * 블로킹 대기 편의 API. 내부 구현:
     *   subscribe(taskId, "0") { event -> if (event.final) future.complete(event) }
     *   future.orTimeout(timeout).whenComplete { _, _ -> subscription.close() }
     */
    fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent>

    /** 터미널 이후 grace period 후 스트림 DEL. 구현은 TTL `EXPIRE` 권장. */
    fun close(taskId: String)

    /** 스트림 키가 Redis 에 아직 존재하는지. grace period 내면 true. */
    fun streamExists(taskId: String): Boolean
}

interface Subscription : AutoCloseable {
    override fun close()
}
