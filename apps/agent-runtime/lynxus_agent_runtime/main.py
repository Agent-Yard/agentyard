from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

from lynxus_common import (
    ReadinessCheck,
    TRACEPARENT_HEADER,
    bind_log_context,
    bind_request_log_context,
    build_readiness_report,
    clear_log_context,
    configure_structured_logging,
)

from .decisioning import execute_agent_turn
from .descriptor_provider import default_descriptor_provider
from .extension_registration import load_extension_registration
from .extension_registry import load_tool_connector_registry, validate_tool_connector_registry
from .http_clients import reset_shared_http_client_registry
from .models import AgentTurnExecutionOutcome, AgentTurnRequest, PlaybookToolTaskRequest, PlaybookToolTaskResult
from .redis_support import RedisSettings, create_redis_client
from .streaming import stream_agent_turn
from .tool_connectors import reset_default_tool_connector_registry, set_default_tool_connector_registry
from .tooling import execute_playbook_tool_task
from .transcript_store import TranscriptStoreSettings, create_transcript_store

LOGGER = logging.getLogger("lynxus-agent-runtime")
INSTANCE_ID = (os.getenv("LYNXUS_INSTANCE_ID") or "lynxus-agent-runtime").strip() or "lynxus-agent-runtime"


def require_internal_bearer(authorization: str | None = Header(default=None, alias="Authorization")) -> None:
    if authorization is None or not authorization.strip():
        raise HTTPException(status_code=401, detail="internal authentication is required")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="invalid internal authentication token")
    expected = (os.getenv("LYNXUS_INTERNAL_AUTH_TOKEN") or "").strip()
    if not expected or token.strip() != expected:
        raise HTTPException(status_code=401, detail="invalid internal authentication token")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global LOGGER
    LOGGER = configure_structured_logging("agent-runtime", "LYNXUS_AGENT_RUNTIME_LOG_LEVEL", "lynxus-agent-runtime")
    extension_registration = load_extension_registration()
    app.state.extension_registration = extension_registration
    descriptor_provider = default_descriptor_provider()
    app.state.extension_descriptor_provider = descriptor_provider
    tool_connector_registry = load_tool_connector_registry(extension_registration, descriptor_provider=descriptor_provider)
    app.state.tool_connector_registry = tool_connector_registry
    set_default_tool_connector_registry(tool_connector_registry)
    LOGGER.info(
        "extension registration loaded",
        extra={
            "instanceId": INSTANCE_ID,
            "registrationConfigDigest": extension_registration.registration_config_digest,
            "registrationCount": len(extension_registration.services),
            "toolConnectorDescriptorIds": tool_connector_registry.descriptor_ids(),
        },
    )
    redis_settings = RedisSettings.from_env()
    redis_client = create_redis_client(redis_settings)
    await redis_client.ping()
    LOGGER.info(
        "redis connectivity verified",
        extra={
            "instanceId": INSTANCE_ID,
            "redisHost": redis_settings.host,
            "redisPort": redis_settings.port,
            "redisDatabase": redis_settings.database,
            "redisSslEnabled": redis_settings.ssl_enabled,
        },
    )
    app.state.redis_client = redis_client
    app.state.redis_settings = redis_settings
    transcript_store = create_transcript_store()
    transcript_store.initialize()
    app.state.transcript_store = transcript_store
    app.state.transcript_store_settings = transcript_store.settings
    LOGGER.info(
        "agent-runtime postgres connectivity verified",
        extra={
            "instanceId": INSTANCE_ID,
            "databaseSchema": "agent_runtime",
        },
    )
    yield
    reset_default_tool_connector_registry()
    reset_shared_http_client_registry()
    await redis_client.aclose()
    transcript_store.close()
    clear_log_context()


app = FastAPI(title="lynxus-agent-runtime", lifespan=lifespan)


@app.middleware("http")
async def bind_traceparent(request: Request, call_next):
    traceparent = bind_request_log_context(request.headers)
    response = await call_next(request)
    response.headers[TRACEPARENT_HEADER] = traceparent
    clear_log_context()
    return response


@app.get("/healthz")
async def healthz() -> JSONResponse:
    async def check_redis() -> dict[str, object]:
        redis_client = getattr(app.state, "redis_client", None)
        if redis_client is None:
            raise RuntimeError("redis client is not initialized")
        redis_settings = getattr(app.state, "redis_settings", RedisSettings.from_env())
        if not await redis_client.ping():
            raise RuntimeError("redis ping returned falsy response")
        return {
            "host": redis_settings.host,
            "port": redis_settings.port,
            "database": redis_settings.database,
        }

    async def check_database() -> dict[str, object]:
        transcript_store = getattr(app.state, "transcript_store", None)
        if transcript_store is None:
            raise RuntimeError("transcript store is not initialized")
        settings = getattr(app.state, "transcript_store_settings", TranscriptStoreSettings.from_env())
        result = transcript_store.check_database()
        return {
            **result,
            "databaseUrlConfigured": bool(settings.database_url),
        }

    report = await build_readiness_report(
        service_name="lynxus-agent-runtime",
        instance_id=INSTANCE_ID,
        checks=[
            ReadinessCheck(
                name="redis",
                probe=check_redis,
            ),
            ReadinessCheck(
                name="database",
                probe=check_database,
            ),
        ],
    )
    return JSONResponse(status_code=report.status_code, content=report.body)


@app.get("/extension/manifest")
async def extension_manifest(
    request: Request,
    _: None = Depends(require_internal_bearer),
) -> Response:
    provider = getattr(request.app.state, "extension_descriptor_provider", None) or default_descriptor_provider()
    return Response(content=provider.canonical_manifest_bytes(), media_type="application/json")


@app.get("/internal/extension-registry/tool-connectors/validation")
async def tool_connector_registry_validation(
    request: Request,
    _: None = Depends(require_internal_bearer),
) -> JSONResponse:
    registry = getattr(request.app.state, "tool_connector_registry", None)
    if registry is not None and registry.validation_result is not None:
        result = registry.validation_result
    else:
        registration_set = getattr(request.app.state, "extension_registration", None)
        provider = getattr(request.app.state, "extension_descriptor_provider", None) or default_descriptor_provider()
        result = validate_tool_connector_registry(registration_set, descriptor_provider=provider)
    status_code = 200 if result.get("status") == "READY" else 503
    return JSONResponse(status_code=status_code, content=result)


@app.post("/agent-turns/execute", response_model=AgentTurnExecutionOutcome)
async def execute_turn(
    request: AgentTurnRequest,
    _: None = Depends(require_internal_bearer),
) -> AgentTurnExecutionOutcome:
    bind_log_context(
        sessionId=request.sessionId,
        customerId=str(request.trigger.payload.get("customerId") or ""),
    )
    outcome, prompt_bundle = execute_agent_turn(request)
    log_extra = {
        "instanceId": INSTANCE_ID,
        "sessionId": request.sessionId,
        "assistantId": request.assistantId,
        "ownerAgentId": request.currentOwner.agentId,
        "runtimeMessageCount": len(prompt_bundle.runtime_messages),
        "llmUsageCount": len(outcome.llmUsage),
        "hasOpenAiCompatibleProvider": bool(
            (os.getenv("LYNXUS_OPENAI_COMPATIBLE_BASE_URL") or "").strip()
            and (os.getenv("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID") or "").strip()
        ),
    }
    if outcome.success and outcome.result is not None:
        LOGGER.info(
            "agent turn executed",
            extra={**log_extra, "action": outcome.result.decision.action},
        )
    else:
        LOGGER.warning(
            "agent turn execution returned failure",
            extra={**log_extra, "failureReason": outcome.failureReason},
        )
    return outcome


@app.post("/agent-turns/execute-stream")
async def execute_turn_stream(
    request: AgentTurnRequest,
    _: None = Depends(require_internal_bearer),
) -> StreamingResponse:
    bind_log_context(
        sessionId=request.sessionId,
        customerId=str(request.trigger.payload.get("customerId") or ""),
    )
    transcript_store = getattr(app.state, "transcript_store", None)
    return StreamingResponse(stream_agent_turn(request, transcript_store=transcript_store), media_type="application/x-ndjson")


@app.post("/playbook-tool-tasks/execute", response_model=PlaybookToolTaskResult)
async def execute_playbook_tool(
    request: PlaybookToolTaskRequest,
    _: None = Depends(require_internal_bearer),
) -> PlaybookToolTaskResult:
    bind_log_context(
        sessionId=request.sessionId,
        playbookRunId=request.playbookRunId,
        nodeKey=request.nodeKey,
    )
    result = execute_playbook_tool_task(request)
    LOGGER.info(
        "playbook tool task executed",
        extra={
            "instanceId": INSTANCE_ID,
            "sessionId": request.sessionId,
            "playbookRunId": request.playbookRunId,
            "playbookId": request.playbookId,
            "nodeKey": request.nodeKey,
            "toolId": request.toolId,
            "toolOperation": request.toolOperation,
            "terminalStatus": result.terminalStatus,
        },
    )
    return result
