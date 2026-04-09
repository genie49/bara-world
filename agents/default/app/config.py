from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    google_api_key: str = ""
    agent_id: str = "default-agent"
    agent_name: str = "default-agent"
    kafka_bootstrap_servers: str = "localhost:30092"
    api_service_url: str = "http://localhost/api/core"
    provider_api_key: str = ""
    heartbeat_interval: float = 20
    port: int = 8090

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}
