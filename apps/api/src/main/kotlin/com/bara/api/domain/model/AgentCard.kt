package com.bara.api.domain.model

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A2A 프로토콜 Agent Card")
data class AgentCard(
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
    val capabilities: AgentCapabilities,
    @field:Schema(description = "Agent 스킬 목록")
    val skills: List<AgentSkill>,
    @field:Schema(description = "Agent 아이콘 URL", example = "https://example.com/icon.png", nullable = true)
    val iconUrl: String? = null,
) {
    @Schema(description = "Agent 기능")
    data class AgentCapabilities(
        @field:Schema(description = "스트리밍 지원 여부", example = "false")
        val streaming: Boolean = false,
        @field:Schema(description = "푸시 알림 지원 여부", example = "false")
        val pushNotifications: Boolean = false,
    )

    @Schema(description = "Agent 스킬")
    data class AgentSkill(
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
}
