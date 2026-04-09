package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskPublisherPort
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class TaskKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : TaskPublisherPort {
    private val mapper = jacksonObjectMapper()

    override fun publish(agentId: String, taskMessage: Map<String, Any?>) {
        val topic = "tasks.$agentId"
        val value = mapper.writeValueAsString(taskMessage)
        kafkaTemplate.send(topic, value)
    }
}
