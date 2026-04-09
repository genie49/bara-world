package com.bara.api.application.port.out

import com.bara.api.domain.model.Agent

interface AgentRepository {
    fun save(agent: Agent): Agent
    fun findById(id: String): Agent?
    fun findByName(name: String): Agent?
    fun findAll(): List<Agent>
    fun deleteById(id: String)
}
