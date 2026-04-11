package com.bara.api.domain.exception

class KafkaPublishException(
    message: String,
    cause: Throwable? = null,
) : A2AException(
    code = A2AErrorCodes.KAFKA_PUBLISH_FAILED,
    message = message,
    cause = cause,
)
