package com.bara.auth.application.port.out
data class GeneratedApiKey(val rawKey: String, val keyHash: String, val keyPrefix: String)
interface ApiKeyGenerator {
    fun generate(): GeneratedApiKey
}
