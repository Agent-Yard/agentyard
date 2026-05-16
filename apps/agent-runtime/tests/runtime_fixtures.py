from __future__ import annotations

import json


class FakeLifespanTranscriptStore:
    settings = type(
        "Settings",
        (),
        {
            "database_url": "postgresql+psycopg://test",
            "turn_execution_retention_seconds": 60,
            "transcript_entry_retention_seconds": 60,
            "retention_sweep_limit": 50,
            "transcript_cache_ttl_seconds": 60,
        },
    )()

    def initialize(self) -> None:
        return None

    def sweep_expired(self) -> dict[str, int]:
        return {
            "turnExecutionsAborted": 0,
            "turnExecutionsDeleted": 0,
            "transcriptEntriesAborted": 0,
            "transcriptEntriesDeleted": 0,
        }

    def close(self) -> None:
        return None


def request_payload() -> dict:
    return {
        "sessionId": "session-1",
        "turnId": "turn-1",
        "turnExecutionId": "turn-1:exec-1",
        "replyMessageId": "session-message-reply-1",
        "assistantId": "assistant-1",
        "assistantReleaseVersion": "2026.04.19",
        "currentOwner": {
            "agentId": "agent-a",
            "name": "Agent A",
            "role": "support",
            "responsibility": "help the customer",
            "model": {
                "resourceId": "model-1",
                "resourceName": "OpenAI Compatible Model",
                "resourceVersionId": "model-ver-1",
                "resourceVersion": "1.0.0",
                "providerType": "OPENAI_COMPATIBLE",
                "modelId": "gpt-test",
                "baseUrl": "https://runtime.example",
                "apiKeyEnvVar": "TEST_OPENAI_COMPATIBLE_API_KEY",
                "temperature": 0,
                "maxTokens": 512,
                "enableThinking": True,
                "reasoningEffort": "high",
            },
            "knowledgeEnabled": True,
            "knowledgeBaseId": "kb-1",
            "knowledgeBinding": {
                "knowledgeBaseId": "kb-1",
                "knowledgeBaseName": "Refund Knowledge",
                "knowledgeReleaseId": "kr-1",
                "knowledgeReleaseVersion": "1.0.0",
                "snapshotId": "snapshot-1",
                "defaultTopK": 5,
                "retrievalMode": "HYBRID",
                "minScore": 0.1,
            },
            "allowedActions": ["REPLY", "RUN_PLAYBOOK"],
            "playbookIds": ["pb-1"],
            "skills": [
                {
                    "resourceId": "skill-1",
                    "resourceName": "Refund Skill",
                    "resourceVersionId": "skill-ver-1",
                    "resourceVersion": "1.0.0",
                    "skillName": "Refund Policy",
                    "skillDesc": "Read refund constraints before answering.",
                    "skillPrompt": "退款时必须先确认订单状态，再决定是否走退款 playbook。",
                }
            ],
            "tools": [
                {
                    "resourceId": "tool-1",
                    "resourceName": "Ticket Tool",
                    "resourceVersionId": "tool-ver-1",
                    "resourceVersion": "1.0.0",
                    "connector": {
                        "connectorType": "simple-http",
                        "accountSnapshot": None,
                        "timeoutSeconds": 15,
                        "retryPolicy": _retry_policy(),
                        "config": {"baseUrl": "https://tool.example"},
                        "operationMappings": {
                            "create_ticket": {"method": "POST", "path": "/invoke", "requestPlacement": "JSON_BODY"}
                        },
                    },
                    "operations": [
                        {
                            "name": "create_ticket",
                            "description": "Create a service ticket.",
                            "inputSchema": json.dumps(
                                {
                                    "type": "object",
                                    "properties": {"subject": {"type": "string"}},
                                    "required": ["subject"],
                                    "additionalProperties": False,
                                },
                                ensure_ascii=False,
                            ),
                            "outputSchema": json.dumps(
                                {
                                    "type": "object",
                                    "properties": {
                                        "ticketId": {"type": "string"},
                                        "status": {"type": "string"},
                                    },
                                    "required": ["ticketId", "status"],
                                    "additionalProperties": False,
                                },
                                ensure_ascii=False,
                            ),
                        }
                    ],
                }
            ],
        },
        "availableAgents": [
            {
                "agentId": "agent-b",
                "name": "Agent B",
                "role": "ops",
                "responsibility": "handle escalations",
                "allowedActions": ["REPLY"],
            }
        ],
        "availablePlaybooks": [
            {
                "playbookId": "pb-1",
                "name": "Playbook 1",
                "description": "refund flow",
            }
        ],
        "sharedState": {"knownPreference": "email"},
        "trigger": {
            "triggerType": "USER_MESSAGE",
            "eventId": "evt-1",
            "turnId": "turn-1",
            "payload": {"text": "帮我发起退款"},
        },
        "messages": [
            {
                "messageId": "msg-1",
                "sessionId": "session-1",
                "sequence": 1,
                "turnId": "turn-1",
                "turnIndex": 0,
                "producerType": "EXTERNAL",
                "externalMessageId": None,
                "clientMessageId": "client-msg-1",
                "occurredAt": "2026-04-19T00:00:01Z",
                "role": "USER",
                "sender": {
                    "senderType": "CUSTOMER",
                    "senderId": "customer-1",
                    "senderName": "customer-1",
                },
                "status": "DELIVERED",
                "blocks": [{"type": "TEXT", "text": "帮我发起退款"}],
                "metadata": {},
                "createdAt": "2026-04-19T00:00:01Z",
                "updatedAt": "2026-04-19T00:00:01Z",
            }
        ],
        "contextEntries": [
            {
                "entryId": "shared-state-snapshot:session-1:1",
                "entryType": "SHARED_STATE_SNAPSHOT",
                "revision": 1,
                "occurredAt": "2026-04-19T00:00:00Z",
                "data": {"sharedState": {"knownPreference": "email"}},
            }
        ],
        "transcriptBootstrap": False,
    }


def _retry_policy() -> dict:
    return {
        "mode": "NONE",
        "maxAttempts": 1,
        "initialDelayMs": 0,
        "maxDelayMs": 0,
        "backoffMultiplier": 1.0,
        "retryableCategories": [],
        "retryableErrorCodes": [],
    }
