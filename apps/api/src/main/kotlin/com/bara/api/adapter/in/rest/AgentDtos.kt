package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.RegisterAgentCommand
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.swagger.v3.oas.annotations.media.Schema

// ── Request ──

@Schema(description = "Agent 등록 요청")
data class RegisterAgentRequest(
    @field:Schema(description = "Agent 이름 (Provider 내 고유)", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Agent Card 정보")
    val agentCard: AgentCardRequest,
) {
    fun toCommand() = RegisterAgentCommand(
        name = name,
        agentCard = AgentCard(
            name = agentCard.name,
            description = agentCard.description,
            version = agentCard.version,
            defaultInputModes = agentCard.defaultInputModes,
            defaultOutputModes = agentCard.defaultOutputModes,
            capabilities = AgentCard.AgentCapabilities(
                streaming = agentCard.capabilities.streaming,
                pushNotifications = agentCard.capabilities.pushNotifications,
            ),
            skills = agentCard.skills.map {
                AgentCard.AgentSkill(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    tags = it.tags,
                    examples = it.examples,
                )
            },
            iconUrl = agentCard.iconUrl,
        ),
    )
}

@Schema(description = "Agent Card 요청")
data class AgentCardRequest(
    @field:Schema(description = "Agent 표시 이름", example = "Translation Agent")
    val name: String,
    @field:Schema(description = "Agent 설명", example = "다국어 번역을 수행하는 AI 에이전트")
    val description: String,
    @field:Schema(description = "버전", example = "1.0.0")
    val version: String,
    @field:Schema(description = "기본 입력 모드", example = "[\"text\"]")
    val defaultInputModes: List<String>,
    @field:Schema(description = "기본 출력 모드", example = "[\"text\"]")
    val defaultOutputModes: List<String>,
    @field:Schema(description = "Agent 기능")
    val capabilities: AgentCapabilitiesRequest = AgentCapabilitiesRequest(),
    @field:Schema(description = "Agent 스킬 목록")
    val skills: List<AgentSkillRequest>,
    @field:Schema(description = "Agent 아이콘 URL", example = "https://example.com/icon.png", nullable = true)
    val iconUrl: String? = null,
)

@Schema(description = "Agent 기능 요청")
data class AgentCapabilitiesRequest(
    @field:Schema(description = "스트리밍 지원 여부", example = "false")
    val streaming: Boolean = false,
    @field:Schema(description = "푸시 알림 지원 여부", example = "false")
    val pushNotifications: Boolean = false,
)

@Schema(description = "Agent 스킬 요청")
data class AgentSkillRequest(
    @field:Schema(description = "스킬 ID", example = "translate")
    val id: String,
    @field:Schema(description = "스킬 이름", example = "번역")
    val name: String,
    @field:Schema(description = "스킬 설명", example = "입력 텍스트를 지정된 언어로 번역합니다")
    val description: String,
    @field:Schema(description = "스킬 태그", example = "[\"translation\", \"multilingual\"]")
    val tags: List<String> = emptyList(),
    @field:Schema(description = "사용 예시", example = "[\"영어를 한국어로 번역해줘\"]")
    val examples: List<String> = emptyList(),
)

// ── Response ──

@Schema(description = "Agent 요약 정보")
data class AgentResponse(
    @field:Schema(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
    val id: String,
    @field:Schema(description = "Agent 이름", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
    val providerId: String,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent) = AgentResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            createdAt = agent.createdAt.toString(),
        )
    }
}

@Schema(description = "Agent 상세 정보")
data class AgentDetailResponse(
    @field:Schema(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
    val id: String,
    @field:Schema(description = "Agent 이름", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
    val providerId: String,
    @field:Schema(description = "Agent Card 정보")
    val agentCard: AgentCard,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent) = AgentDetailResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            agentCard = agent.agentCard,
            createdAt = agent.createdAt.toString(),
        )
    }
}

@Schema(description = "Agent 목록 응답")
data class AgentListResponse(
    @field:Schema(description = "Agent 목록")
    val agents: List<AgentResponse>,
)

@Schema(description = "에러 응답")
data class ErrorResponse(
    @field:Schema(description = "에러 코드", example = "agent_not_found")
    val error: String,
    @field:Schema(description = "에러 메시지", example = "Agent를 찾을 수 없습니다")
    val message: String,
)
