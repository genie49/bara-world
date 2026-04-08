from fastapi import APIRouter

from app.models.a2a import AgentCard

router = APIRouter()

AGENT_CARD = AgentCard(
    name="Bara Default Agent",
    description="Bara 플랫폼 기본 범용 대화 에이전트",
    version="0.1.0",
    default_input_modes=["text"],
    default_output_modes=["text"],
    capabilities={"streaming": True, "pushNotifications": False},
    skills=[
        {
            "id": "chat",
            "name": "대화",
            "description": "자연어 대화를 수행합니다",
            "tags": ["chat", "general"],
            "examples": ["안녕하세요", "오늘 날씨 어때?"],
        }
    ],
)


@router.get("/.well-known/agent.json")
async def get_agent_card() -> dict:
    return AGENT_CARD.model_dump(by_alias=True)
