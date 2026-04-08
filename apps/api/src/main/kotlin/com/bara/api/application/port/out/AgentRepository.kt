package com.bara.api.application.port.out

import com.bara.api.domain.model.Agent

interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findAll(): List<Agent>
    fun findByProviderIdAndName(providerId: String, name: String): Agent?
    fun deleteById(id: String)
}
