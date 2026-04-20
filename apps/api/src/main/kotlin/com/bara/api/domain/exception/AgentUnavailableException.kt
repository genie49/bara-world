package com.bara.api.domain.exception

class AgentUnavailableException : A2AException(
    code = A2AErrorCodes.AGENT_UNAVAILABLE,
    message = "Agent is not available",
)
