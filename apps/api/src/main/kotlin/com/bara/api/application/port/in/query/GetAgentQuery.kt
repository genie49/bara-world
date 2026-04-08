package com.bara.api.application.port.`in`.query

import com.bara.api.domain.model.Agent

interface GetAgentQuery {
    fun getById(agentId: String): Agent
}
