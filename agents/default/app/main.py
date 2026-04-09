from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.kafka.consumer import TaskConsumer
from app.kafka.producer import ResultProducer
from app.logging import RequestLoggingMiddleware, setup_logging
from app.registry import RegistryClient
from app.routes.health import router as health_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()

    registry = RegistryClient(settings)
    await registry.register()

    agent = ChatAgent(api_key=settings.google_api_key)
    producer = ResultProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = TaskConsumer(
        agent_id=settings.agent_id,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        agent=agent,
        producer=producer,
    )

    await producer.start()
    await consumer.start()

    import asyncio

    hb_task = asyncio.create_task(registry.heartbeat_loop())

    yield

    hb_task.cancel()
    try:
        await hb_task
    except asyncio.CancelledError:
        pass
    await consumer.stop()
    await producer.stop()
    await registry.close()


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    return app


app = create_app()
