package com.bara.api.domain.model

data class A2AArtifact(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APart> = emptyList(),
)
