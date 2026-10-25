"""Model-facing collaboration contracts. Identity and call IDs are trusted envelope fields."""
from __future__ import annotations

from typing import Any

from .semantic import SemanticToolDefinition


def object_schema(properties: dict[str, Any], required: list[str] | None = None) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required or [], "additionalProperties": False}


STRING = {"type": "string", "minLength": 1}
OBJECT = {"type": "object"}
SCOPE = {"type": "string", "enum": ["PLATFORM", "ORGANIZATION", "TASK", "SESSION", "TEMPLATE", "RESOURCE", "PARTICIPANT"]}
GRANT = object_schema({"operation": STRING, "scopeKind": SCOPE, "scopeId": STRING,
    "delegable": {"type": "boolean"}, "expiresAt": {"type": "string", "format": "date-time"}},
    ["operation", "scopeKind", "scopeId"])
PAGING = {"cursor": STRING, "limit": {"type": "integer", "minimum": 1, "maximum": 100}}
DECISION_SOURCE = {"type": "string", "description": "TOP-LEVEL argument, never inside data: the current human message ID that explicitly authorizes this decision. Never invent consent or reuse an unrelated instruction."}
COMMAND_DATA = {"type": "object", "properties": {
    "kind": {"type": "string", "description": "PUT_CONTEXT contribution kind: FACT, HYPOTHESIS, PROPOSAL, DECISION, QUESTION, NOTE, SUMMARY or ARTIFACT (use ARTIFACT for a deliverable/report). LINK_TASK relation kind: CHILD, DEPENDS_ON or RELATED."},
    "title": STRING, "entryId": STRING,
    "body": {"type": "object", "description": "Structured contribution content. For prose or a report use {markdown: 'the full report text'}, never a plain string."},
    "source": {"type": "object", "description": "Structured provenance such as {entryIds: ['id']} or {tool: 'supplier_lookup', reportId: '...'}, never a plain string."},
    "visibleToRoles": {"type": "array", "items": STRING},
    "entryRevision": {"type": "integer", "minimum": 1},
    "contentRevision": {"type": "integer", "minimum": 1},
}, "additionalProperties": True}


def definition(name: str, description: str, properties: dict[str, Any], required: list[str] | None = None) -> SemanticToolDefinition:
    return SemanticToolDefinition(name=name, description=description, input_schema=object_schema(properties, required))


COLLABORATION_TOOLS = [
    definition("task_list", "Find ongoing work visible to this participant. Read before creating duplicate work.",
        {"organizationId": STRING, "query": STRING, **PAGING}),
    definition("task_read", "Read current Task metadata and separately paged context.items and decisions.items. Follow context.nextCursor using cursor and decisions.nextCursor using decisionCursor for full history. Membership does not expose private Session history.",
        {"taskId": STRING, "decisionCursor": STRING, **PAGING}, ["taskId"]),
    definition("task_create", "Create persistent work only when follow-up, collaboration or delivery warrants it. When acting on a user's request include that sourceMessageId so the user remains the business initiator and final confirmer.",
        {"title": STRING, "goal": STRING, "organizationId": STRING, "attributes": OBJECT,
         "sourceMessageId": DECISION_SOURCE}, ["title", "goal"]),
    definition("task_command", "Apply a versioned Task command; inspect returned status/contentRevision/submissionInvalidated. PUT_CONTEXT data: kind, title, body, visibleToRoles, source; edits also entryId/entryRevision. SUBMIT data: optional comment; submit the latest shared content AFTER all review notes, making status SUBMITTED so the confirmer can act. New material invalidates an earlier submission: re-read and SUBMIT again when ready. TRANSITION data: status (ACTIVE, WAITING, PAUSED, CANCELLED); WAITING is missing information, NOT awaiting confirmation. Never directly mark COMPLETED. SET_PARTICIPANT data: actorId,roles. CREATE_CHILD data: title,goal,participants. CONFIRM, CONFIRM_STAGE and REQUEST_CHANGES data must identify contentRevision; TRANSFER_CONFIRMATION data needs participantId. CONFIRM_STAGE accepts a phase without closing long-running work. Final confirmation belongs to the initiator; use sourceMessageId for an explicit human decision.",
        {"taskId": STRING, "expectedRevision": {"type": "integer", "minimum": 0},
         "kind": {"type": "string", "enum": ["UPDATE", "TRANSITION", "SET_PARTICIPANT", "REMOVE_PARTICIPANT",
             "PUT_CONTEXT", "SET_CONTEXT_STATE", "CREATE_CHILD", "LINK_TASK", "UNLINK_TASK", "LINK_SESSION", "UNLINK_SESSION",
             "SUBMIT", "CONFIRM", "CONFIRM_STAGE", "REQUEST_CHANGES", "TRANSFER_CONFIRMATION"]},
         "data": COMMAND_DATA, "sourceMessageId": DECISION_SOURCE}, ["taskId", "expectedRevision", "kind", "data"]),
    definition("participant_list", "Discover participants whom you may contact; Agent participants identify concrete persistent instances.",
        {"organizationId": STRING, "taskId": STRING, "query": STRING, **PAGING}),
    definition("template_list", "Discover authorized preconfigured role templates. Templates describe roles and do not grant permissions.",
        {"query": STRING, **PAGING}),
    definition("agent_create", "Create a persistent Agent Session from an authorized role template and send its initial instruction. Delegate only required grants you may delegate. A Task defaults to focused execution, but may have multiple instances.",
        {"templateVersionId": STRING, "name": STRING, "taskId": STRING, "instruction": STRING,
         "taskRoles": {"type": "array", "items": STRING},
         "grants": {"type": "array", "items": GRANT}}, ["templateVersionId", "instruction"]),
    definition("message_send", "Send a direct collaboration message or ask a person for information. A question is ordinary collaboration; it does not put the Session into human handoff mode. Include work and reply references when known.",
        {"recipientId": STRING, "taskId": STRING, "replyToMessageId": STRING,
         "kind": {"type": "string", "enum": ["MESSAGE", "DELEGATION", "RESULT", "QUESTION", "EVENT", "CONTROL"]},
         "body": OBJECT}, ["recipientId", "kind", "body"]),
    definition("grant_create", "Delegate an explicitly authorized operation and scope to another participant; neither a template nor a Task relation grants access.",
        {"participantId": STRING, **GRANT["properties"]}, ["participantId", "operation", "scopeKind", "scopeId"]),
    definition("grant_revoke", "Revoke a grant. Subsequent tool calls recheck current authorization.",
        {"grantId": STRING}, ["grantId"]),
    definition("schedule_create", "Arrange a durable future wake-up, optionally recurring. Use for follow-up work without a browser or process remaining open.",
        {"dueAt": {"type": "string", "format": "date-time"}, "instruction": STRING,
         "taskId": STRING, "intervalSeconds": {"type": "integer", "minimum": 60}}, ["dueAt", "instruction"]),
    definition("schedule_cancel", "Cancel an existing wake-up arrangement.", {"scheduleId": STRING}, ["scheduleId"]),
    definition("subscription_create", "Subscribe this Session to authorized object events; unrelated private history remains inaccessible.",
        {"scopeKind": {"type": "string", "enum": ["TASK", "RESOURCE", "TEMPLATE"]}, "scopeId": STRING, "eventKinds": {"type": "array", "items": STRING, "minItems": 1}},
        ["scopeKind", "scopeId", "eventKinds"]),
    definition("subscription_cancel", "Cancel an existing event subscription.", {"subscriptionId": STRING}, ["subscriptionId"]),
    definition("session_read", "Read authorized instance details, messages and waiting work, without silently merging another instance's private context.",
        {"sessionId": STRING, **PAGING}, ["sessionId"]),
    definition("session_status", "Read actual activation liveness for an instance linked to shared work, without reading private history. Supply taskId for a colleague. lifecycle ACTIVE means the instance exists; only execution RUNNING means it is currently executing. WAITING means a concrete dependency; IDLE means no current activation. Never infer execution from lifecycle.",
        {"sessionId": STRING, "taskId": STRING}, ["sessionId"]),
    definition("session_control", "Apply an explicit lifecycle decision to your instance or an instance you may control. Pausing preserves durable context; closing ends the instance.",
        {"sessionId": STRING, "status": {"type": "string", "enum": ["ACTIVE", "PAUSED", "CLOSED"]}}, ["sessionId", "status"]),
    definition("session_memory", "Read or update this instance's structured working memory. Preserve decisions, ongoing work IDs, waiting questions and resource references. Supply a patch to update it.",
        {"patch": OBJECT}),
    definition("tool_run_start", "Start a versioned Playbook as a durable tool run. The result will return to this Session as a message; starting it does not change Agent identity or complete the Task.",
        {"playbookVersionId": STRING, "taskId": STRING, "input": OBJECT}, ["playbookVersionId", "input"]),
    definition("tool_run_resume", "Respond to a specific waiting tool run after an authorized human decision or external result.",
        {"toolRunId": STRING, "waitingId": STRING, "sourceMessageId": DECISION_SOURCE, "payload": OBJECT},
        ["toolRunId", "waitingId", "payload"]),
    definition("resource_read", "Read a versioned Task contribution or explicitly authorized Session history. For TOOL/PLAYBOOK/SKILL/KNOWLEDGE return their authorized description; use the declared invocation, knowledge_search or knowledge_read tool to obtain external contents. A reference never grants access by itself.",
        {"reference": {"type": "object", "properties": {
            "kind": {"type": "string", "enum": ["TASK_CONTENT", "SESSION_HISTORY", "TOOL", "PLAYBOOK", "SKILL", "KNOWLEDGE"]},
            "resourceId": {"type": "string", "description": "For SESSION_HISTORY this is the target Session ID; otherwise the mounted resource/version ID."},
            "taskId": STRING, "entryId": STRING, "revision": {"type": "integer", "minimum": 1},
            "operation": STRING, "after": {"type": "integer", "minimum": 0},
            "limit": {"type": "integer", "minimum": 1, "maximum": 200}, "query": STRING,
        }, "required": ["kind"], "additionalProperties": False}}, ["reference"]),
]

COLLABORATION_TOOL_NAMES = frozenset(tool.name for tool in COLLABORATION_TOOLS)
