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

RUN uv export --quiet --frozen --package agentyard-agent-runtime --no-dev --no-emit-workspace --format requirements-txt --output-file /requirements.txt

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
COPY packages/extension-protocol/json-schema packages/extension-protocol/json-schema
COPY packages/extension-protocol/openapi packages/extension-protocol/openapi
COPY packages/extension-protocol/src packages/extension-protocol/src
COPY packages/extension-sdk-python/src packages/extension-sdk-python/src
COPY packages/python-common/src packages/python-common/src
COPY apps/agent-runtime/agentyard_agent_runtime apps/agent-runtime/agentyard_agent_runtime

RUN uv build --package agentyard-extension-protocol --out-dir /dist
RUN uv build --package agentyard-extension-sdk --out-dir /dist
RUN uv build --package agentyard-python-common --out-dir /dist
RUN uv build --package agentyard-agent-runtime --out-dir /dist

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

EXPOSE 8090

ENTRYPOINT ["uvicorn", "agentyard_agent_runtime.main:app", "--host", "0.0.0.0", "--port", "8090"]
