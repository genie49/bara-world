package com.bara.api.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface AgentMongoDataRepository : MongoRepository<AgentDocument, String> {
    fun findByName(name: String): AgentDocument?
}
