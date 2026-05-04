from __future__ import annotations

import json
from typing import Any

from .semantic import SemanticMessage, SemanticToolDefinition

SYSTEM_REMINDER_OPEN_TAG = "<system-reminder>"
SYSTEM_REMINDER_CLOSE_TAG = "</system-reminder>"


def render_openai_tool_definitions(definitions: list[SemanticToolDefinition]) -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": definition.name,
                "description": _render_openai_tool_description(definition),
                "parameters": definition.input_schema,
            },
        }
        for definition in definitions
    ]


def render_openai_runtime_message(message: SemanticMessage) -> dict[str, Any]:
    if message.kind == "user_turn":
        return {"role": "user", "content": message.content}
    if message.kind == "assistant_turn":
        rendered: dict[str, Any] = {"role": "assistant", "content": message.content}
        if message.tool_calls:
            rendered["tool_calls"] = [
                {
                    "id": tool_call.call_id,
                    "type": "function",
                    "function": {
                        "name": tool_call.tool_name,
                        "arguments": json.dumps(tool_call.arguments, ensure_ascii=False),
                    },
                }
                for tool_call in message.tool_calls
            ]
        return rendered
    if message.kind == "tool_result":
        return {
            "role": "tool",
            "tool_call_id": message.tool_call_id,
            "content": message.content,
        }
    if message.kind == "system_event":
        return {"role": "user", "content": render_system_reminder(message.content)}
    raise ValueError(f"Unsupported semantic message kind: {message.kind}")


def render_system_reminder(content: str) -> str:
    return f"{SYSTEM_REMINDER_OPEN_TAG}{content}{SYSTEM_REMINDER_CLOSE_TAG}"


def _render_openai_tool_description(definition: SemanticToolDefinition) -> str:
    if not definition.output_schema:
        return definition.description
    return (
        definition.description
        + "\n\nOutput JSON schema:\n"
        + json.dumps(definition.output_schema, ensure_ascii=False, sort_keys=True)
    )
