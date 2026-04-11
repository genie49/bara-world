package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val registryAgentUseCase: RegistryAgentUseCase,
    private val heartbeatAgentUseCase: HeartbeatAgentUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
) {

    @PostMapping
    fun register(
        @RequestHeader("X-Provider-Id") providerId: String,
        @RequestBody request: RegisterAgentRequest,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = registerAgentUseCase.register(providerId, request.toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentDetailResponse.from(agent))
    }

    @GetMapping
    fun list(): ResponseEntity<AgentListResponse> {
        val agents = listAgentsQuery.listAll()
        return ResponseEntity.ok(AgentListResponse(agents.map { AgentResponse.from(it) }))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<AgentDetailResponse> {
        val agent = getAgentQuery.getById(id)
        return ResponseEntity.ok(AgentDetailResponse.from(agent))
    }

    @GetMapping("/{id}/.well-known/agent.json")
    fun getAgentCard(@PathVariable id: String): ResponseEntity<Any> {
        val card = getAgentCardQuery.getCardById(id)
        return ResponseEntity.ok(card)
    }

    @PostMapping("/{agentName}/registry")
    fun registry(
        @RequestHeader("X-Provider-Id") providerId: String,
        @PathVariable agentName: String,
    ): ResponseEntity<Void> {
        registryAgentUseCase.registry(providerId, agentName)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{agentName}/heartbeat")
    fun heartbeat(
        @RequestHeader("X-Provider-Id") providerId: String,
        @PathVariable agentName: String,
    ): ResponseEntity<Void> {
        heartbeatAgentUseCase.heartbeat(providerId, agentName)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{id}")
    fun delete(
        @RequestHeader("X-Provider-Id") providerId: String,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        deleteAgentUseCase.delete(providerId, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{agentName}/message:send")
    fun sendMessage(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @RequestBody request: SendMessageApiRequest,
    ): CompletableFuture<ResponseEntity<A2ATaskDto>> {
        val text = request.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = request.message.messageId,
            text = text,
            contextId = request.contextId,
        )
        return sendMessageUseCase.sendBlocking(userId, agentName, sendRequest)
            .thenApply { ResponseEntity.ok(it) }
    }
}
