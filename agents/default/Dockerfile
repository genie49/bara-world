FROM python:3.12-slim AS build
WORKDIR /app

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

COPY agents/default/pyproject.toml agents/default/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY agents/default/app/ app/

FROM python:3.12-slim
WORKDIR /app

COPY --from=build /app/.venv /app/.venv
COPY --from=build /app/app app/

ENV PATH="/app/.venv/bin:$PATH"

EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
