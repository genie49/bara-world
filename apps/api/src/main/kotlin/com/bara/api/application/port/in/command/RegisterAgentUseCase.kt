package com.bara.api.application.port.`in`.command

import com.bara.api.domain.model.Agent

interface RegisterAgentUseCase {
    fun register(providerId: String, command: RegisterAgentCommand): Agent
}
