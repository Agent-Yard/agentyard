from __future__ import annotations

import logging
import os
import secrets
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

import structlog

TRACEPARENT_HEADER = "traceparent"
SESSION_ID_HEADER = "X-Lynxus-Session-Id"
WORKFLOW_ID_HEADER = "X-Lynxus-Workflow-Id"
CUSTOMER_ID_HEADER = "X-Lynxus-Customer-Id"
USER_ID_HEADER = "X-Lynxus-User-Id"

TRACE_ID_KEY = "traceId"
SPAN_ID_KEY = "spanId"
SESSION_ID_KEY = "sessionId"
WORKFLOW_ID_KEY = "workflowId"
CUSTOMER_ID_KEY = "customerId"
USER_ID_KEY = "userId"

_LOG_CONTEXT_KEYS = (
    TRACE_ID_KEY,
    SPAN_ID_KEY,
    SESSION_ID_KEY,
    WORKFLOW_ID_KEY,
    CUSTOMER_ID_KEY,
    USER_ID_KEY,
)


@dataclass(frozen=True)
class TraceContext:
    trace_id: str
    span_id: str
    sampled: str = "01"

    @property
    def traceparent(self) -> str:
        return f"00-{self.trace_id}-{self.span_id}-{self.sampled}"


def configure_structured_logging(service_name: str, level_env_var: str, logger_name: str | None = None) -> logging.Logger:
    level_name = os.getenv(level_env_var, "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    log_format = os.getenv("LYNXUS_LOG_FORMAT", "json").strip().lower() or "json"
    renderer: Any = (
        structlog.dev.ConsoleRenderer(colors=False)
        if log_format == "console"
        else structlog.processors.JSONRenderer()
    )
    timestamper = structlog.processors.TimeStamper(fmt="iso", utc=True, key="timestamp")

    def add_service(_: logging.Logger, __: str, event_dict: dict[str, Any]) -> dict[str, Any]:
        event_dict.setdefault("service", service_name)
        if "event" in event_dict and "message" not in event_dict:
            event_dict["message"] = event_dict["event"]
        for key in _LOG_CONTEXT_KEYS:
            event_dict.setdefault(key, None)
        return event_dict

    shared_processors = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        timestamper,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        add_service,
    ]

    formatter = structlog.stdlib.ProcessorFormatter(
        processor=renderer,
        foreign_pre_chain=shared_processors,
    )
    handler = logging.StreamHandler()
    handler.setFormatter(formatter)

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(level)

    for intercepted_logger in ("uvicorn", "uvicorn.error", "uvicorn.access", "fastapi"):
        std_logger = logging.getLogger(intercepted_logger)
        std_logger.handlers.clear()
        std_logger.propagate = True

    structlog.configure(
        processors=[
            *shared_processors,
            structlog.stdlib.ProcessorFormatter.wrap_for_formatter,
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
        cache_logger_on_first_use=True,
    )

    target_logger = logging.getLogger(logger_name or service_name)
    target_logger.handlers.clear()
    target_logger.addHandler(handler)
    target_logger.setLevel(level)
    target_logger.propagate = False
    return target_logger


def clear_log_context() -> None:
    structlog.contextvars.clear_contextvars()


def bind_log_context(**values: str | None) -> None:
    bound_values = {
        key: value
        for key, value in values.items()
        if value is not None and str(value).strip()
    }
    if bound_values:
        structlog.contextvars.bind_contextvars(**bound_values)


def bind_request_log_context(headers: Mapping[str, str | None]) -> str:
    trace_context = _trace_context_from_header(headers.get(TRACEPARENT_HEADER))
    clear_log_context()
    bind_log_context(
        traceId=trace_context.trace_id,
        spanId=trace_context.span_id,
        sessionId=headers.get(SESSION_ID_HEADER),
        workflowId=headers.get(WORKFLOW_ID_HEADER),
        customerId=headers.get(CUSTOMER_ID_HEADER),
        userId=headers.get(USER_ID_HEADER),
    )
    return trace_context.traceparent


def current_traceparent() -> str:
    current = structlog.contextvars.get_contextvars()
    trace_id = str(current.get(TRACE_ID_KEY) or "").strip()
    span_id = str(current.get(SPAN_ID_KEY) or "").strip()
    if len(trace_id) != 32 or len(span_id) != 16:
        return _new_trace_context().traceparent
    return TraceContext(trace_id=trace_id, span_id=span_id).traceparent


def _trace_context_from_header(traceparent: str | None) -> TraceContext:
    if not traceparent:
        return _new_trace_context()
    parts = traceparent.strip().lower().split("-")
    if len(parts) != 4:
        return _new_trace_context()
    _, trace_id, _, sampled = parts
    if len(trace_id) != 32 or len(sampled) != 2:
        return _new_trace_context()
    return TraceContext(trace_id=trace_id, span_id=secrets.token_hex(8), sampled=sampled)


def _new_trace_context() -> TraceContext:
    return TraceContext(trace_id=secrets.token_hex(16), span_id=secrets.token_hex(8))
