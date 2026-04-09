package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private data class HeartbeatMessage(
    val agent_id: String,
    val timestamp: String,
)

@Component
class HeartbeatConsumer(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    @KafkaListener(topics = ["heartbeat"], groupId = "api-service")
    fun handleHeartbeat(message: String) {
        val heartbeat = try {
            mapper.readValue<HeartbeatMessage>(message)
        } catch (e: Exception) {
            logger.warn("Failed to parse heartbeat message", e)
            return
        }

        val agent = agentRepository.findById(heartbeat.agent_id)
        if (agent == null) {
            logger.warn("Heartbeat from unknown agent: {}", heartbeat.agent_id)
            return
        }

        if (!agentRegistryPort.isRegistered(agent.name)) {
            logger.debug("Heartbeat from unregistered agent: {}", agent.name)
            return
        }

        agentRegistryPort.refreshTtl(agent.name)
    }
}
