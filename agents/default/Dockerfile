FROM python:3.12-slim AS build
WORKDIR /app

COPY agents/default/pyproject.toml .
RUN pip install --no-cache-dir .

COPY agents/default/app/ app/

FROM python:3.12-slim
WORKDIR /app

COPY --from=build /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=build /usr/local/bin/uvicorn /usr/local/bin/uvicorn
COPY --from=build /app/app app/

EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
