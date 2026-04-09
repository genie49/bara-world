package com.bara.api.domain.exception

class AgentNotRegisteredException(agentName: String) :
    RuntimeException("Agent is not registered: $agentName")
