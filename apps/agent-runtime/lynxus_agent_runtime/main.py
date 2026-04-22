from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

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
from .models import AgentTurnExecutionOutcome, AgentTurnRequest, PlaybookToolTaskRequest, PlaybookToolTaskResult
from .redis_support import RedisSettings, create_redis_client
from .tooling import execute_playbook_tool_task

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
    yield
    await redis_client.aclose()
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

    report = await build_readiness_report(
        service_name="lynxus-agent-runtime",
        instance_id=INSTANCE_ID,
        checks=[
            ReadinessCheck(
                name="redis",
                probe=check_redis,
            )
        ],
    )
    return JSONResponse(status_code=report.status_code, content=report.body)


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
