package com.bara.api.adapter.`in`.rest.a2a

data class A2ATaskDto(
    val id: String,
    val contextId: String,
    val status: A2ATaskStatusDto,
    val artifacts: List<A2AArtifactDto> = emptyList(),
    val kind: String = "task",
    val metadata: Map<String, Any?> = emptyMap(),
)

data class A2ATaskStatusDto(
    val state: String,
    val message: A2AMessageDto? = null,
    val timestamp: String? = null,
)

data class A2AMessageDto(
    val messageId: String,
    val role: String,
    val parts: List<A2APartDto>,
)

data class A2APartDto(
    val kind: String,
    val text: String,
)

data class A2AArtifactDto(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APartDto> = emptyList(),
)
