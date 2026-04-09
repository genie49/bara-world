package com.bara.api.adapter.out.persistence

import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.model.Agent
import org.springframework.stereotype.Repository

@Repository
class AgentMongoRepository(
    private val dataRepository: AgentMongoDataRepository,
) : AgentRepository {

    override fun save(agent: Agent): Agent =
        dataRepository.save(AgentDocument.fromDomain(agent)).toDomain()

    override fun findById(id: String): Agent? =
        dataRepository.findById(id).orElse(null)?.toDomain()

    override fun findByName(name: String): Agent? =
        dataRepository.findByName(name)?.toDomain()

    override fun findAll(): List<Agent> =
        dataRepository.findAll().map { it.toDomain() }

    override fun deleteById(id: String) =
        dataRepository.deleteById(id)
}
