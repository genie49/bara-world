package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcRequest
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.command.StreamMessageUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.`in`.query.SubscribeTaskQuery
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val registryAgentUseCase: RegistryAgentUseCase,
    private val heartbeatAgentUseCase: HeartbeatAgentUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val streamMessageUseCase: StreamMessageUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
    private val getTaskQuery: GetTaskQuery,
    private val subscribeTaskQuery: SubscribeTaskQuery,
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
        @RequestBody envelope: JsonRpcRequest<SendMessageParams>,
    ): CompletableFuture<ResponseEntity<JsonRpcResponse<A2ATaskDto>>> {
        val params = envelope.params
            ?: throw IllegalArgumentException("JSON-RPC params is required")
        val text = params.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = params.message.messageId,
            text = text,
            contextId = params.contextId,
        )
        val returnImmediately = params.configuration?.returnImmediately == true

        return if (returnImmediately) {
            val dto = sendMessageUseCase.sendAsync(userId, agentName, sendRequest)
            CompletableFuture.completedFuture(
                ResponseEntity.ok(JsonRpcResponse(id = envelope.id, result = dto)),
            )
        } else {
            sendMessageUseCase.sendBlocking(userId, agentName, sendRequest)
                .thenApply { dto ->
                    ResponseEntity.ok(JsonRpcResponse(id = envelope.id, result = dto))
                }
        }
    }

    @PostMapping("/{agentName}/message:stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sendMessageStream(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @RequestBody envelope: JsonRpcRequest<SendMessageParams>,
    ): SseEmitter {
        val params = envelope.params
            ?: throw IllegalArgumentException("JSON-RPC params is required")
        val text = params.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = params.message.messageId,
            text = text,
            contextId = params.contextId,
        )
        return streamMessageUseCase.stream(userId, agentName, envelope.id, sendRequest)
    }

    @GetMapping("/{agentName}/tasks/{taskId}")
    fun getTask(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @PathVariable taskId: String,
    ): ResponseEntity<JsonRpcResponse<A2ATaskDto>> {
        val dto = getTaskQuery.getTask(userId = userId, taskId = taskId)
        return ResponseEntity.ok(JsonRpcResponse(id = null, result = dto))
    }

    @GetMapping("/{agentName}/tasks/{taskId}:subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribeTask(
        @RequestHeader("X-User-Id") userId: String,
        @RequestHeader(value = "Last-Event-ID", required = false) lastEventId: String?,
        @PathVariable agentName: String,
        @PathVariable taskId: String,
    ): SseEmitter = subscribeTaskQuery.subscribe(userId, taskId, lastEventId)
}
