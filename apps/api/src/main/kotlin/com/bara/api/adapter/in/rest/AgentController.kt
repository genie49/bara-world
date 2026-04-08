package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.domain.model.AgentCard
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Agent", description = "Agent 등록/조회/삭제")
@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
) {

    @Operation(
        summary = "Agent 등록",
        description = "새로운 Agent를 등록한다. Agent 이름은 Provider 내에서 고유해야 하며, 중복 시 409를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Agent 등록 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentDetailResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "Agent 이름 중복",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping
    fun register(
        @Parameter(description = "Traefik이 주입하는 Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-Provider-Id") providerId: String,
        @RequestBody request: RegisterAgentRequest,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = registerAgentUseCase.register(providerId, request.toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentDetailResponse.from(agent))
    }

    @Operation(
        summary = "Agent 목록 조회",
        description = "등록된 모든 Agent 목록을 조회한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentListResponse::class))],
            ),
        ],
    )
    @GetMapping
    fun list(): ResponseEntity<AgentListResponse> {
        val agents = listAgentsQuery.listAll()
        return ResponseEntity.ok(AgentListResponse(agents.map { AgentResponse.from(it) }))
    }

    @Operation(
        summary = "Agent 상세 조회",
        description = "Agent ID로 상세 정보를 조회한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentDetailResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/{id}")
    fun getById(
        @Parameter(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = getAgentQuery.getById(id)
        return ResponseEntity.ok(AgentDetailResponse.from(agent))
    }

    @Operation(
        summary = "Agent Card 조회 (A2A 프로토콜)",
        description = "A2A 프로토콜 표준 경로로 Agent Card JSON을 반환한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Agent Card 조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentCard::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/{id}/.well-known/agent.json")
    fun getAgentCard(
        @Parameter(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        val card = getAgentCardQuery.getCardById(id)
        return ResponseEntity.ok(card)
    }

    @Operation(
        summary = "Agent 삭제",
        description = "Agent를 삭제한다. 본인이 등록한 Agent만 삭제 가능하며, 다른 Provider의 Agent 삭제 시도 시 404를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없거나 권한 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(description = "Traefik이 주입하는 Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-Provider-Id") providerId: String,
        @Parameter(description = "삭제할 Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        deleteAgentUseCase.delete(providerId, id)
        return ResponseEntity.noContent().build()
    }
}
