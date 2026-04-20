package com.bara.api.domain.exception

class AgentTimeoutException(
    message: String = "Agent did not respond within timeout",
) : A2AException(
    code = A2AErrorCodes.AGENT_TIMEOUT,
    message = message,
)
