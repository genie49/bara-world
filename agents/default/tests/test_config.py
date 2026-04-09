from app.config import Settings


def _settings(**overrides) -> Settings:
    """env_file을 무시하고 명시된 값만으로 Settings 생성."""
    return Settings(_env_file=None, **overrides)


def test_settings_defaults():
    settings = _settings(google_api_key="test-key")
    assert settings.agent_id == "default-agent"
    assert settings.kafka_bootstrap_servers == "localhost:30092"
    assert settings.port == 8090


def test_settings_custom_values():
    settings = _settings(
        google_api_key="custom-key",
        agent_id="agent-999",
        kafka_bootstrap_servers="kafka:29092",
        port=9000,
    )
    assert settings.google_api_key == "custom-key"
    assert settings.agent_id == "agent-999"
    assert settings.kafka_bootstrap_servers == "kafka:29092"
    assert settings.port == 9000
