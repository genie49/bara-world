package com.bara.api.e2e.support

import com.bara.test.KafkaContainerSupport
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E2E 전용 fake agent.
 * 특정 agentId에 해당하는 `tasks.<agentId>` 토픽을 구독해서, 등록된 [Behavior]에 맞춰
 * `results.api`에 결과를 즉시 produce한다. 진짜 agent 프로세스 없이 blocking send end-to-end를 검증할 때 사용.
 *
 * 사용 패턴:
 * ```
 * val fake = FakeAgentKafka().apply { start() }
 * fake.onAgent(agentId, Behavior.completed("pong"))
 * // message:send 호출
 * fake.stop()
 * ```
 *
 * - behavior는 agentId별로 덮어쓸 수 있다 (마지막 값 적용).
 * - [Behavior.Silent]인 경우 결과를 produce하지 않아 API가 timeout을 맞게 된다.
 */
class FakeAgentKafka(
    private val bootstrapServers: String = KafkaContainerSupport.bootstrapServers,
    private val resultTopic: String = "results.api",
) {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val behaviors = ConcurrentHashMap<String, Behavior>()
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fake-agent-kafka").apply { isDaemon = true }
    }

    private lateinit var consumer: KafkaConsumer<String, String>
    private val producer: KafkaProducer<String, String> = KafkaProducer(
        Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
        },
    )

    fun onAgent(agentId: String, behavior: Behavior) {
        // 스레드 경쟁 방지: consumer는 poll thread만 터치한다.
        // 여기선 behaviors 맵만 갱신하고, pollLoop가 다음 iteration에서 subscription을 조정한다.
        behaviors[agentId] = behavior
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        consumer = KafkaConsumer(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "fake-agent-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            },
        )
        executor.submit { pollLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor.shutdownNow()
        runCatching { consumer.wakeup() }
        runCatching { producer.flush() }
        runCatching { producer.close(Duration.ofSeconds(2)) }
    }

    private fun pollLoop() {
        var lastSubscribed: Set<String> = emptySet()
        try {
            while (running.get()) {
                val desired = behaviors.keys.map { "tasks.$it" }.toSet()
                if (desired != lastSubscribed) {
                    if (desired.isEmpty()) {
                        consumer.unsubscribe()
                    } else {
                        consumer.subscribe(desired)
                    }
                    lastSubscribed = desired
                }
                if (desired.isEmpty()) {
                    Thread.sleep(50)
                    continue
                }
                val records = consumer.poll(Duration.ofMillis(200))
                for (record in records) {
                    handleRecord(record)
                }
            }
        } catch (_: org.apache.kafka.common.errors.WakeupException) {
            // shutdown
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { consumer.close(Duration.ofSeconds(1)) }
        }
    }

    private fun handleRecord(record: ConsumerRecord<String, String>) {
        val topic = record.topic() // "tasks.<agentId>"
        val agentId = topic.removePrefix("tasks.")
        val behavior = behaviors[agentId] ?: return
        val payload = runCatching { mapper.readValue(record.value(), Map::class.java) }
            .getOrNull() ?: return
        // TaskKafkaPublisher는 snake_case로 직렬화: task_id, context_id.
        val taskId = (payload["task_id"] ?: payload["taskId"]) as? String ?: return
        val contextId = (payload["context_id"] ?: payload["contextId"]) as? String ?: return

        val result = behavior.buildResult(taskId, contextId) ?: return
        producer.send(ProducerRecord(resultTopic, taskId, mapper.writeValueAsString(result)))
        producer.flush()
    }

    /**
     * agent가 어떻게 응답할지 정의. 각 Behavior는 task의 (taskId, contextId)를 받아 결과 payload를 만든다.
     * null을 반환하면 produce하지 않음 (→ API timeout 경로 유도).
     */
    interface Behavior {
        fun buildResult(taskId: String, contextId: String): Map<String, Any?>?

        /** 아무 결과도 produce하지 않아 API 호출이 timeout을 맞게 한다. */
        object Silent : Behavior {
            override fun buildResult(taskId: String, contextId: String): Map<String, Any?>? = null
        }

        companion object {
            fun completed(text: String, artifactName: String = "reply"): Behavior = object : Behavior {
                override fun buildResult(taskId: String, contextId: String): Map<String, Any?> = mapOf(
                    "taskId" to taskId,
                    "contextId" to contextId,
                    "state" to "completed",
                    "artifact" to mapOf(
                        "artifactId" to "art-${UUID.randomUUID()}",
                        "name" to artifactName,
                        "parts" to listOf(mapOf("kind" to "text", "text" to text)),
                    ),
                    "final" to true,
                )
            }

            fun failed(errorCode: String, errorMessage: String): Behavior = object : Behavior {
                override fun buildResult(taskId: String, contextId: String): Map<String, Any?> = mapOf(
                    "taskId" to taskId,
                    "contextId" to contextId,
                    "state" to "failed",
                    "errorCode" to errorCode,
                    "errorMessage" to errorMessage,
                    "final" to true,
                )
            }
        }
    }
}
