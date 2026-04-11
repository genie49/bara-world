package com.bara.api.domain.exception

class AgentTimeoutException(
    message: String = "Agent did not respond within timeout",
) : RuntimeException(message)
