package com.bara.api.application.port.`in`.command

interface RegistryAgentUseCase {
    fun registry(providerId: String, agentName: String)
}
