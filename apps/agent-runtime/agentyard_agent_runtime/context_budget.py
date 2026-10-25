"""Bound model input without deleting authoritative history or tool receipts."""
from __future__ import annotations

from copy import deepcopy
import json
import os
from typing import Any


def encoded(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def bounded_tool_result(result: dict[str, Any], *, activation_id: str, call_id: str) -> dict[str, Any]:
    text = encoded(result)
    if len(text) <= 12_000:
        return result
    return {"truncated": True, "totalChars": len(text),
        "source": {"activationId": activation_id, "callId": call_id}, "nextOffset": 8_000,
        "readMore": "Use tool_result_read with this activationId/callId and character offset. The full durable receipt remains available; this preview is incomplete.", "preview": text[:8_000]}


def bounded_provider_messages(messages: list[dict[str, Any]], *, tool_schema_chars: int = 0,
                              max_chars: int | None = None) -> list[dict[str, Any]]:
    """A conservative character budget, not a tokenizer estimate; retain tool pairs.

    The latest incoming context/message and current system instruction are pinned.
    Only the model projection is shortened; SQL history and receipts are untouched.
    """
    limit = max_chars if max_chars is not None else int(os.getenv("AGENTYARD_AGENT_RUNTIME_CONTEXT_MAX_CHARS", "80000"))
    budget = limit - tool_schema_chars
    result = deepcopy(messages)
    for message in result:
        content = message.get("content")
        if message.get("role") != "system" and isinstance(content, str) and len(content) > 12_000:
            message["content"] = content[:10_000] + "\n[truncated model projection; full original content is in Session history. Use session_history to locate its source, then tool_result_read to page through large results.]"
    groups: list[list[dict[str, Any]]] = []
    for message in result:
        if message.get("role") == "tool" and groups and groups[-1][0].get("tool_calls"):
            groups[-1].append(message)
        else:
            groups.append([message])
    pinned = {i for i, group in enumerate(groups) if group[0].get("role") == "system"}
    user_groups = [i for i, group in enumerate(groups) if group[0].get("role") == "user"]
    pinned.update(user_groups[-2:])
    # Runtime continuation prompts must not evict the actual instruction/context.
    for marker in ("Incoming collaboration message:\n", "Current authorized work context:\n"):
        sources = [i for i, group in enumerate(groups) if marker in str(group[0].get("content", ""))]
        if sources:
            pinned.add(sources[-1])
    pinned.add(len(groups) - 1)
    kept = set(range(len(groups)))
    sizes = [len(encoded(group)) for group in groups]
    total = sum(sizes)
    removed = 0
    for i in range(len(groups)):
        if total <= budget - 500:
            break
        if i not in pinned:
            kept.remove(i)
            total -= sizes[i]
            removed += len(groups[i])
    if total > budget - 500:
        raise ValueError("Current system instructions, tool schemas or required messages exceed the configured context character budget; reduce mounted capabilities or split the input. Full Session evidence remains stored.")
    rendered = [message for i, group in enumerate(groups) if i in kept for message in group]
    if removed:
        rendered.insert(1, {"role": "user", "content": f"[context projection omitted {removed} older messages to fit its budget. They remain in durable Session history. Use session_history/history_summary to retrieve decisions and unfinished work before assuming anything missing.]"})
    return rendered
