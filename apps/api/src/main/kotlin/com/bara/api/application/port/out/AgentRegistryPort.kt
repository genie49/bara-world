package com.bara.api.application.port.out

interface AgentRegistryPort {
    fun register(agentName: String, agentId: String)
    fun isRegistered(agentName: String): Boolean
    fun getAgentId(agentName: String): String?
    fun refreshTtl(agentName: String)
}
