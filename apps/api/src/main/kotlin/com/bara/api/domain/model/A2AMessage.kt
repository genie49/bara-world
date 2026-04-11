package com.bara.api.domain.model

data class A2AMessage(
    val messageId: String,
    val role: String,          // "user" | "agent"
    val parts: List<A2APart>,
)

data class A2APart(
    val kind: String,           // "text"
    val text: String,
)
