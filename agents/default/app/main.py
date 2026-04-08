from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.kafka.consumer import TaskConsumer
from app.kafka.heartbeat import HeartbeatLoop
from app.kafka.producer import ResultProducer
from app.logging import RequestLoggingMiddleware, setup_logging
from app.routes.health import router as health_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()

    agent = ChatAgent(api_key=settings.google_api_key)
    producer = ResultProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = TaskConsumer(
        agent_id=settings.agent_id,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        agent=agent,
        producer=producer,
    )
    heartbeat = HeartbeatLoop(agent_id=settings.agent_id, producer=producer)

    await producer.start()
    await consumer.start()
    await heartbeat.start()

    yield

    await heartbeat.stop()
    await consumer.stop()
    await producer.stop()


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    return app


app = create_app()
