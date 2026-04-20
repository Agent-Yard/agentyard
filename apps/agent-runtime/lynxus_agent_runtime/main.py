from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request

from lynxus_common import (
    TRACEPARENT_HEADER,
    bind_log_context,
    bind_request_log_context,
    clear_log_context,
    configure_structured_logging,
)

from .decisioning import execute_agent_turn
from .models import AgentTurnRequest, AgentTurnResult, PlaybookToolTaskRequest, PlaybookToolTaskResult
from .redis_support import RedisSettings, create_redis_client
from .tooling import execute_playbook_tool_task

LOGGER = logging.getLogger("lynxus-agent-runtime")


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
            "redisHost": redis_settings.host,
            "redisPort": redis_settings.port,
            "redisDatabase": redis_settings.database,
            "redisSslEnabled": redis_settings.ssl_enabled,
        },
    )
    app.state.redis_client = redis_client
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
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/agent-turns/execute", response_model=AgentTurnResult)
async def execute_turn(
    request: AgentTurnRequest,
    _: None = Depends(require_internal_bearer),
) -> AgentTurnResult:
    bind_log_context(
        sessionId=request.sessionId,
        customerId=str(request.trigger.payload.get("customerId") or ""),
    )
    result, prompt_bundle = execute_agent_turn(request)
    LOGGER.info(
        "agent turn executed",
        extra={
            "sessionId": request.sessionId,
            "assistantId": request.assistantId,
            "ownerAgentId": request.currentOwner.agentId,
            "action": result.decision.action,
            "runtimeMessageCount": len(prompt_bundle.runtime_messages),
            "hasOpenAiCompatibleProvider": bool(
                (os.getenv("LYNXUS_OPENAI_COMPATIBLE_BASE_URL") or "").strip()
                and (os.getenv("LYNXUS_OPENAI_COMPATIBLE_MODEL_ID") or "").strip()
            ),
        },
    )
    return result


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
