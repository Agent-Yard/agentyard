from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http import HTTPStatus
from typing import Any

ProbeResult = dict[str, Any] | None
Probe = Callable[[], ProbeResult | Awaitable[ProbeResult]]


@dataclass(frozen=True)
class ReadinessCheck:
    name: str
    probe: Probe
    context: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ReadinessReport:
    ready: bool
    status_code: int
    body: dict[str, Any]


async def build_readiness_report(
    service_name: str,
    instance_id: str,
    checks: Sequence[ReadinessCheck],
) -> ReadinessReport:
    dependencies: dict[str, dict[str, Any]] = {}
    ready = True
    for check in checks:
        dependency = dict(check.context)
        try:
            probe_result = check.probe()
            if inspect.isawaitable(probe_result):
                probe_result = await probe_result
            dependency["status"] = "UP"
            if probe_result:
                dependency.update(probe_result)
        except Exception as exc:
            ready = False
            dependency["status"] = "DOWN"
            dependency["detail"] = _error_detail(exc)
        dependencies[check.name] = dependency
    return ReadinessReport(
        ready=ready,
        status_code=HTTPStatus.OK if ready else HTTPStatus.SERVICE_UNAVAILABLE,
        body={
            "status": "UP" if ready else "DOWN",
            "service": service_name,
            "instanceId": instance_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "dependencies": dependencies,
        },
    )


def _error_detail(error: Exception) -> str:
    detail = str(error).strip()
    if detail:
        return detail
    return error.__class__.__name__
