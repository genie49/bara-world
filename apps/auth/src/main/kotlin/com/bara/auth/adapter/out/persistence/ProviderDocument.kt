package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.Provider
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "providers")
data class ProviderDocument(
    @Id val id: String,
    @Indexed(unique = true) val userId: String,
    val name: String,
    val status: String,
    val createdAt: Instant,
) {
    fun toDomain(): Provider = Provider(
        id = id, userId = userId, name = name,
        status = Provider.ProviderStatus.valueOf(status), createdAt = createdAt,
    )
    companion object {
        fun fromDomain(p: Provider): ProviderDocument = ProviderDocument(
            id = p.id, userId = p.userId, name = p.name,
            status = p.status.name, createdAt = p.createdAt,
        )
    }
}
