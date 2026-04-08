from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.logging import RequestLoggingMiddleware, setup_logging
from app.routes.agent_card import router as agent_card_router
from app.routes.health import router as health_router
from app.routes.task import router as task_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()
    app.state.agent = ChatAgent(
        model_name=settings.model_name,
        api_key=settings.google_api_key,
    )
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    app.include_router(agent_card_router)
    app.include_router(task_router)
    return app


app = create_app()
