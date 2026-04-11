package com.bara.api.application.port.out

interface TaskPublisherPort {
    /**
     * Kafka tasks.{agentId} 발행. ack 대기 후 반환.
     * 실패 시 KafkaPublishException.
     */
    fun publish(agentId: String, payload: TaskMessagePayload)
}
