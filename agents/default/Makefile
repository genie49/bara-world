.PHONY: dev start test lint fmt sync

# 개발 모드 (hot reload)
dev:
	uv run uvicorn app.main:app --port 8090 --reload

# 프로덕션 모드
start:
	uv run uvicorn app.main:app --port 8090

# 테스트
test:
	uv run python -m pytest -v

# 린트
lint:
	uv run ruff check .

# 포맷
fmt:
	uv run ruff format .

# 의존성 동기화
sync:
	uv sync
