from __future__ import annotations

import logging
import os
import httpx
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from .models import ActivationOutcome

from agentyard_common import (
    ReadinessCheck,
    TRACEPARENT_HEADER,
    bind_log_context,
    bind_request_log_context,
    build_readiness_report,
    clear_log_context,
    configure_structured_logging,
)

from .descriptor_provider import default_descriptor_provider
from .extension_registration import load_extension_registration
from .extension_registry import load_tool_connector_registry, validate_tool_connector_registry
from .http_clients import reset_shared_http_client_registry
from .models import ActivationStart, ActivationStreamFrame, ToolRunNodeRequest, PlaybookToolTaskResult
from .control_plane import activation_runtime, publish_frame
from starlette.concurrency import run_in_threadpool
from .redis_support import RedisSettings, create_redis_client
from .streaming import stream_activation
from .tool_connectors import reset_default_tool_connector_registry, set_default_tool_connector_registry
from .tooling import execute_tool_run_node, ToolRunExecutionPaused
from .transcript_store import DEFAULT_SCHEMA_NAME, TranscriptStoreSettings, TurnExecutionContext, create_transcript_cache, create_transcript_store

LOGGER = logging.getLogger("agentyard-agent-runtime")
INSTANCE_ID = (os.getenv("AGENTYARD_INSTANCE_ID") or "agentyard-agent-runtime").strip() or "agentyard-agent-runtime"


async def _serialize_ndjson_frames(frames: AsyncIterator[ActivationStreamFrame]) -> AsyncIterator[str]:
    async for frame in frames:
        yield frame.model_dump_json() + "\n"


def require_internal_bearer(authorization: str | None = Header(default=None, alias="Authorization")) -> None:
    if authorization is None or not authorization.strip():
        raise HTTPException(status_code=401, detail="internal authentication is required")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise HTTPException(status_code=401, detail="invalid internal authentication token")
    expected = (os.getenv("AGENTYARD_INTERNAL_AUTH_TOKEN") or "").strip()
    if not expected or token.strip() != expected:
        raise HTTPException(status_code=401, detail="invalid internal authentication token")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global LOGGER
    LOGGER = configure_structured_logging("agent-runtime", "AGENTYARD_AGENT_RUNTIME_LOG_LEVEL", "agentyard-agent-runtime")
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
    transcript_store_settings = TranscriptStoreSettings.from_env()
    transcript_cache = create_transcript_cache(redis_settings, transcript_store_settings)
    transcript_store = create_transcript_store(transcript_store_settings, transcript_cache=transcript_cache)
    transcript_store.initialize()
    retention_sweep_result = transcript_store.sweep_expired()
    app.state.transcript_store = transcript_store
    app.state.transcript_store_settings = transcript_store.settings
    LOGGER.info(
        "agent-runtime postgres connectivity verified",
        extra={
            "instanceId": INSTANCE_ID,
            "databaseSchema": DEFAULT_SCHEMA_NAME,
            "retentionSweepResult": retention_sweep_result,
            "activationLeaseSeconds": transcript_store.settings.activation_lease_seconds,
            "contextWindowMessages": transcript_store.settings.context_window_messages,
            "retentionSweepLimit": transcript_store.settings.retention_sweep_limit,
            "transcriptCacheTtlSeconds": transcript_store.settings.transcript_cache_ttl_seconds,
        },
    )
    yield
    reset_default_tool_connector_registry()
    reset_shared_http_client_registry()
    await redis_client.aclose()
    transcript_store.close()
    clear_log_context()


app = FastAPI(title="agentyard-agent-runtime", lifespan=lifespan)


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
        service_name="agentyard-agent-runtime",
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


def _require_transcript_store():
    store = getattr(app.state, "transcript_store", None)
    if store is None:
        raise HTTPException(status_code=503, detail="durable context store is not initialized")
    return store


async def _published_activation_frames(runtime, store):
    # One producer for both transports. The API resolves the channel and principal
    # from the activation; the Worker must never relay the same frames a second time.
    frames = stream_activation(runtime, transcript_store=store)
    try:
        async for frame in frames:
            try:
                await run_in_threadpool(publish_frame, frame)
            except httpx.HTTPError as error:
                # Presentation transport must not turn a completed external write
                # into a failed activation. Durable transcript and final Message
                # delivery have their own authoritative persistence paths.
                LOGGER.warning("activation presentation delivery unavailable", extra={
                    "activationId": frame.activationId, "frameId": frame.frameId,
                    "errorType": type(error).__name__})
            yield frame
    finally:
        await frames.aclose()


@app.post("/activations/execute", response_model=ActivationOutcome)
async def execute_activation(request: ActivationStart, _: None = Depends(require_internal_bearer)) -> ActivationOutcome:
    store = _require_transcript_store()
    runtime = await run_in_threadpool(activation_runtime, request.activationId)
    bind_log_context(sessionId=runtime.sessionId, participantId=runtime.participantId, activationId=runtime.activationId)
    async for frame in _published_activation_frames(runtime, store):
        if frame.kind == "FINAL_OUTCOME":
            return ActivationOutcome.model_validate(frame.payload["outcome"])
    return ActivationOutcome(success=False, failureReason="activation ended without a durable outcome")


@app.post("/activations/execute-stream")
async def execute_activation_stream(request: ActivationStart, _: None = Depends(require_internal_bearer)) -> StreamingResponse:
    store = _require_transcript_store()
    runtime = await run_in_threadpool(activation_runtime, request.activationId)
    bind_log_context(sessionId=runtime.sessionId, participantId=runtime.participantId, activationId=runtime.activationId)
    return StreamingResponse(_serialize_ndjson_frames(_published_activation_frames(runtime, store)),
        media_type="application/x-ndjson")


@app.post("/tool-run-nodes/execute", response_model=PlaybookToolTaskResult)
async def execute_tool_node(request: ToolRunNodeRequest, _: None = Depends(require_internal_bearer)) -> PlaybookToolTaskResult:
    store = _require_transcript_store()
    bind_log_context(toolRunId=request.toolRunId, nodeKey=request.nodeKey)
    try:
        return await run_in_threadpool(execute_tool_run_node, request, store)
    except ToolRunExecutionPaused as error:
        raise HTTPException(status_code=423, detail="EXECUTION_PAUSED") from error


@app.get("/sessions/{session_id}/history")
async def read_session_history(session_id: str, participantId: str = Query(min_length=1),
        after: int = Query(default=0, ge=0), limit: int = Query(default=50, ge=1, le=200),
        query: str | None = None, _: None = Depends(require_internal_bearer)) -> dict:
    context = TurnExecutionContext(session_id, participantId, "inspection", "inspection", "inspection")
    try:
        return await run_in_threadpool(_require_transcript_store().history, context, after=after, limit=limit, query=query)
    except ValueError as error:
        raise HTTPException(status_code=403, detail=str(error)) from error


@app.get("/sessions/{session_id}/summary")
async def read_session_summary(session_id: str, participantId: str = Query(min_length=1),
        _: None = Depends(require_internal_bearer)) -> dict:
    context = TurnExecutionContext(session_id, participantId, "inspection", "inspection", "inspection")
    try:
        return await run_in_threadpool(_require_transcript_store().memory, context)
    except ValueError as error:
        raise HTTPException(status_code=403, detail=str(error)) from error
