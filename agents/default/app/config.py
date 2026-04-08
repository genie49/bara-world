from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    google_api_key: str = ""
    port: int = 8090

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}
