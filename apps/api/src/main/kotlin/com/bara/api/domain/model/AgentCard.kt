package com.bara.api.domain.model

data class AgentCard(
    val name: String,
    val description: String,
    val version: String,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val capabilities: AgentCapabilities,
    val skills: List<AgentSkill>,
    val iconUrl: String? = null,
) {
    data class AgentCapabilities(
        val streaming: Boolean = false,
        val pushNotifications: Boolean = false,
    )

    data class AgentSkill(
        val id: String,
        val name: String,
        val description: String,
        val tags: List<String> = emptyList(),
        val examples: List<String> = emptyList(),
    )
}
