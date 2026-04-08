import os

from app.config import Settings


def test_settings_defaults():
    os.environ.setdefault("GOOGLE_API_KEY", "test-key")
    settings = Settings()
    assert settings.model_name == "gemini-2.5-flash-lite-preview-06-17"
    assert settings.port == 8090


def test_settings_custom_values():
    settings = Settings(
        google_api_key="custom-key",
        model_name="gemini-2.0-flash",
        port=9000,
    )
    assert settings.google_api_key == "custom-key"
    assert settings.model_name == "gemini-2.0-flash"
    assert settings.port == 9000
