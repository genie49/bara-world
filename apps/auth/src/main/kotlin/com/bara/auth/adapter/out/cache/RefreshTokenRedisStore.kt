package com.bara.auth.adapter.out.cache

import com.bara.auth.application.port.out.RefreshTokenStore
import com.bara.auth.application.port.out.StoredRefreshToken
import com.bara.auth.config.RefreshTokenProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RefreshTokenRedisStore(
    private val redis: StringRedisTemplate,
    private val props: RefreshTokenProperties,
) : RefreshTokenStore {

    private val mapper = ObjectMapper()

    override fun save(userId: String, jti: String, family: String) {
        val value = mapper.writeValueAsString(mapOf("jti" to jti, "family" to family))
        redis.opsForValue().set(keyOf(userId), value, Duration.ofSeconds(props.expirySeconds))
    }

    override fun find(userId: String): StoredRefreshToken? {
        val json = redis.opsForValue().get(keyOf(userId)) ?: return null
        val map: Map<String, String> = mapper.readValue(json, object : TypeReference<Map<String, String>>() {})
        return StoredRefreshToken(jti = map["jti"]!!, family = map["family"]!!)
    }

    override fun delete(userId: String) {
        redis.delete(keyOf(userId))
    }

    override fun saveGrace(jti: String) {
        redis.opsForValue().set(graceKeyOf(jti), "valid", Duration.ofSeconds(props.gracePeriodSeconds))
    }

    override fun isGraceValid(jti: String): Boolean {
        return redis.hasKey(graceKeyOf(jti)) == true
    }

    private fun keyOf(userId: String) = "refresh:$userId"
    private fun graceKeyOf(jti: String) = "refresh:grace:$jti"
}
