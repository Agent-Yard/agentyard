from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal


SemanticMessageKind = Literal["user_turn", "assistant_turn", "system_event", "tool_result"]


@dataclass(frozen=True)
class SemanticToolCall:
    call_id: str
    tool_name: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SemanticToolResult:
    tool_call_id: str
    content: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SemanticToolDefinition:
    name: str
    description: str
    input_schema: dict[str, Any] = field(default_factory=dict)
    output_schema: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class SemanticMessage:
    kind: SemanticMessageKind
    content: str
    tool_calls: tuple[SemanticToolCall, ...] = ()
    tool_call_id: str | None = None
