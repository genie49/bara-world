package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.domain.exception.InvalidOAuthStateException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisOAuthStateStore(
    private val redis: StringRedisTemplate,
) : OAuthStateStore {

    override fun issue(): String {
        val state = UUID.randomUUID().toString()
        redis.opsForValue().set(keyOf(state), "", TTL)
        return state
    }

    override fun consume(state: String) {
        val key = keyOf(state)
        if (redis.hasKey(key) != true) {
            throw InvalidOAuthStateException()
        }
        redis.delete(key)
    }

    private fun keyOf(state: String): String = "oauth:state:$state"

    companion object {
        private val TTL: Duration = Duration.ofSeconds(300)
    }
}
