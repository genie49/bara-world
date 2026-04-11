package com.bara.api.adapter.out.persistence

import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class TaskMongoRepository(
    private val dataRepository: TaskMongoDataRepository,
    private val mongoTemplate: MongoTemplate? = null,
) : TaskRepositoryPort {

    override fun save(task: Task): Task =
        dataRepository.save(TaskDocument.fromDomain(task)).toDomain()

    override fun findById(id: String): Task? =
        dataRepository.findById(id).orElse(null)?.toDomain()

    override fun updateState(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean {
        val template = mongoTemplate
            ?: return fallbackUpdate(id, state, statusMessage, artifacts, errorCode, errorMessage, updatedAt, completedAt, expiredAt)
        val update = Update()
            .set("state", state.name)
            .set("updatedAt", updatedAt)
        statusMessage?.let { update.set("statusMessage", A2AMessageDoc.fromDomain(it)) }
        if (artifacts.isNotEmpty()) update.set("artifacts", artifacts.map(A2AArtifactDoc::fromDomain))
        errorCode?.let { update.set("errorCode", it) }
        errorMessage?.let { update.set("errorMessage", it) }
        completedAt?.let { update.set("completedAt", it) }
        expiredAt?.let { update.set("expiredAt", it) }

        val result = template.updateFirst(
            Query(Criteria.where("_id").`is`(id)),
            update,
            TaskDocument::class.java,
        )
        return result.modifiedCount > 0
    }

    private fun fallbackUpdate(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean {
        val existing = dataRepository.findById(id).orElse(null) ?: return false
        val updated = existing.copy(
            state = state.name,
            statusMessage = statusMessage?.let(A2AMessageDoc::fromDomain) ?: existing.statusMessage,
            artifacts = if (artifacts.isNotEmpty()) artifacts.map(A2AArtifactDoc::fromDomain) else existing.artifacts,
            errorCode = errorCode ?: existing.errorCode,
            errorMessage = errorMessage ?: existing.errorMessage,
            updatedAt = updatedAt,
            completedAt = completedAt ?: existing.completedAt,
            expiredAt = expiredAt ?: existing.expiredAt,
        )
        dataRepository.save(updated)
        return true
    }
}
