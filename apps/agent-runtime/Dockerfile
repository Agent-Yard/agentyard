FROM ghcr.io/astral-sh/uv:python3.11-bookworm AS builder

WORKDIR /workspace

COPY pyproject.toml uv.lock ./
COPY apps/agent-runtime/pyproject.toml apps/agent-runtime/pyproject.toml
COPY apps/knowledge-service/pyproject.toml apps/knowledge-service/pyproject.toml
COPY packages/python-common/pyproject.toml packages/python-common/pyproject.toml
COPY packages/python-common/src packages/python-common/src
COPY apps/agent-runtime/lynxus_agent_runtime apps/agent-runtime/lynxus_agent_runtime

RUN uv build --package lynxus-python-common --out-dir /dist
RUN uv build --package lynxus-agent-runtime --out-dir /dist

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

COPY --from=builder /dist /dist

RUN pip install --no-cache-dir /dist/*.whl

EXPOSE 8090

ENTRYPOINT ["uvicorn", "lynxus_agent_runtime.main:app", "--host", "0.0.0.0", "--port", "8090"]
