package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.AgentRegistryPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

private const val KEY_PREFIX = "agent:registry:"
private val TTL = Duration.ofSeconds(60)

@Component
class AgentRegistryRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
) : AgentRegistryPort {

    override fun register(agentName: String, agentId: String) {
        redisTemplate.opsForValue().set("$KEY_PREFIX$agentName", agentId, TTL)
    }

    override fun isRegistered(agentName: String): Boolean =
        redisTemplate.hasKey("$KEY_PREFIX$agentName")

    override fun getAgentId(agentName: String): String? =
        redisTemplate.opsForValue().get("$KEY_PREFIX$agentName")

    override fun refreshTtl(agentName: String) {
        redisTemplate.expire("$KEY_PREFIX$agentName", TTL)
    }
}
