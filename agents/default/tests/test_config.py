import os

from app.config import Settings


def test_settings_defaults():
    os.environ.setdefault("GOOGLE_API_KEY", "test-key")
    settings = Settings()
    assert settings.port == 8090


def test_settings_custom_values():
    settings = Settings(
        google_api_key="custom-key",
        port=9000,
    )
    assert settings.google_api_key == "custom-key"
    assert settings.port == 9000
