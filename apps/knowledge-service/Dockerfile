FROM ghcr.io/astral-sh/uv:python3.11-bookworm AS requirements

WORKDIR /workspace

ARG UV_INDEX_URL
ARG UV_EXTRA_INDEX_URL

COPY pyproject.toml uv.lock ./
COPY apps/agent-runtime/pyproject.toml apps/agent-runtime/pyproject.toml
COPY apps/knowledge-service/pyproject.toml apps/knowledge-service/pyproject.toml
COPY packages/extension-protocol/pyproject.toml packages/extension-protocol/pyproject.toml
COPY packages/extension-sdk-python/pyproject.toml packages/extension-sdk-python/pyproject.toml
COPY packages/python-common/pyproject.toml packages/python-common/pyproject.toml

RUN uv export --quiet --frozen --package agentyard-knowledge-service --no-dev --no-emit-workspace --format requirements-txt --output-file /requirements.txt

FROM ghcr.io/astral-sh/uv:python3.11-bookworm AS builder

WORKDIR /workspace

ARG UV_INDEX_URL
ARG UV_EXTRA_INDEX_URL

COPY pyproject.toml uv.lock ./
COPY apps/agent-runtime/pyproject.toml apps/agent-runtime/pyproject.toml
COPY apps/knowledge-service/pyproject.toml apps/knowledge-service/pyproject.toml
COPY packages/extension-protocol/pyproject.toml packages/extension-protocol/pyproject.toml
COPY packages/extension-sdk-python/pyproject.toml packages/extension-sdk-python/pyproject.toml
COPY packages/python-common/pyproject.toml packages/python-common/pyproject.toml
COPY packages/python-common/src packages/python-common/src
COPY apps/knowledge-service/agentyard_knowledge_service apps/knowledge-service/agentyard_knowledge_service

RUN uv build --package agentyard-python-common --out-dir /dist
RUN uv build --package agentyard-knowledge-service --out-dir /dist

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

ARG PIP_INDEX_URL
ARG PIP_EXTRA_INDEX_URL
ARG PIP_TRUSTED_HOST

COPY --from=requirements /requirements.txt /requirements.txt

RUN pip install --no-cache-dir -r /requirements.txt

COPY --from=builder /dist /dist

RUN pip install --no-cache-dir --no-deps /dist/*.whl && rm -rf /dist /requirements.txt

EXPOSE 8091

ENTRYPOINT ["uvicorn", "agentyard_knowledge_service.main:app", "--host", "0.0.0.0", "--port", "8091"]
