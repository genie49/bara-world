package com.bara.common.openapi

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.openapi")
class OpenApiProperties(
    var title: String = "Bara API",
    var version: String = "0.0.1",
    var description: String = "",
)
