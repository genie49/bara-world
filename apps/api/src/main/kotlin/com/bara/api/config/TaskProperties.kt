package com.bara.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.api.task")
data class TaskProperties(
    val blockTimeoutSeconds: Long = 30,
    val kafkaPublishTimeoutSeconds: Long = 5,
    val streamGracePeriodSeconds: Long = 60,
    val mongoTtlDays: Long = 7,
)
