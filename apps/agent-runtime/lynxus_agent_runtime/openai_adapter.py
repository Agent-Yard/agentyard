from __future__ import annotations

import json
from typing import Any

from .semantic import SemanticMessage, SemanticToolCall, SemanticToolDefinition, SemanticToolResult


def render_openai_messages(
    instruction: str,
    capabilities: dict[str, Any],
    response_contract: dict[str, Any],
    runtime_messages: list[SemanticMessage],
) -> list[dict[str, Any]]:
    system_sections = [
        instruction,
        "Capabilities:\n" + json.dumps(capabilities, ensure_ascii=False),
        "Response contract:\n" + json.dumps(response_contract, ensure_ascii=False),
    ]
    messages: list[dict[str, Any]] = [{"role": "system", "content": "\n\n".join(system_sections)}]
    messages.extend(render_openai_runtime_message(message) for message in runtime_messages)
    return messages


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


def parse_openai_tool_calls(message: dict[str, Any]) -> tuple[str, list[SemanticToolCall]]:
    content = str(message.get("content") or "")
    tool_calls = message.get("tool_calls") or []
    semantic_tool_calls: list[SemanticToolCall] = []
    for tool_call in tool_calls:
        function_call = tool_call.get("function")
        if not isinstance(function_call, dict):
            raise ValueError("tool call must contain function payload")
        tool_name = str(function_call.get("name") or "").strip()
        if not tool_name:
            raise ValueError("tool call function name is required")
        raw_arguments = function_call.get("arguments") or "{}"
        arguments = _parse_tool_arguments(raw_arguments)
        semantic_tool_calls.append(
            SemanticToolCall(
                call_id=str(tool_call.get("id") or "").strip(),
                tool_name=tool_name,
                arguments=arguments,
            )
        )
    return content, semantic_tool_calls


def assistant_tool_call_message(content: str, tool_calls: list[SemanticToolCall]) -> SemanticMessage:
    return SemanticMessage(
        kind="assistant_turn",
        content=content,
        tool_calls=tuple(tool_calls),
    )


def tool_result_message(result: SemanticToolResult) -> SemanticMessage:
    return SemanticMessage(
        kind="tool_result",
        content=json.dumps(result.content, ensure_ascii=False),
        tool_call_id=result.tool_call_id,
    )


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
    return {"role": "system", "content": message.content}


def _parse_tool_arguments(raw_arguments: Any) -> dict[str, Any]:
    if isinstance(raw_arguments, dict):
        return raw_arguments
    if not isinstance(raw_arguments, str):
        raise ValueError("tool call arguments must be a JSON string")
    text = raw_arguments.strip() or "{}"
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("tool call arguments must decode to a JSON object")
    return parsed


def _render_openai_tool_description(definition: SemanticToolDefinition) -> str:
    if not definition.output_schema:
        return definition.description
    return (
        definition.description
        + "\n\nOutput JSON schema:\n"
        + json.dumps(definition.output_schema, ensure_ascii=False, sort_keys=True)
    )
