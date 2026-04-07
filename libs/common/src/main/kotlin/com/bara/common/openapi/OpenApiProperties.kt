package com.bara.common.openapi

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.openapi")
data class OpenApiProperties(
    val title: String = "Bara API",
    val version: String = "0.0.1",
    val description: String = "",
)
