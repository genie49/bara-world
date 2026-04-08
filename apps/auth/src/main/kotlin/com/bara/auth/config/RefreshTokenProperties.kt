package com.bara.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.auth.refresh-token")
data class RefreshTokenProperties(
    val audience: String,
    val expirySeconds: Long,
    val gracePeriodSeconds: Long,
)
