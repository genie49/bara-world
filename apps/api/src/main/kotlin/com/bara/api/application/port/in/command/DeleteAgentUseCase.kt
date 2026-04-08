package com.bara.api.application.port.`in`.command

interface DeleteAgentUseCase {
    fun delete(providerId: String, agentId: String)
}
