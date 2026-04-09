package com.bara.api.application.port.`in`.command

interface HeartbeatAgentUseCase {
    fun heartbeat(providerId: String, agentName: String)
}
