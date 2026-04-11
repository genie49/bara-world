package com.bara.api.domain.exception

class KafkaPublishException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
