from __future__ import annotations

import json
from contextlib import contextmanager
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from agentyard_agent_runtime.transcript_store import Base, PostgresTranscriptStore, TranscriptStoreSettings


class FakeLifespanTranscriptStore:
    settings = TranscriptStoreSettings(database_url="postgresql+psycopg://test")
    def initialize(self): pass
    def sweep_expired(self): return {"activationLeasesExpired": 0, "transcriptEntriesDeleted": 0}
    def close(self): pass


def request_payload() -> dict:
    return json.loads("{\"activationId\":\"activation-1\",\"sessionId\":\"session-1\",\"participantId\":\"participant-agent-1\",\"templateVersionId\":\"role-version-1\",\"agent\":{\"name\":\"Agent A\",\"role\":\"support\",\"responsibility\":\"help the customer\",\"model\":{\"resourceId\":\"model-1\",\"resourceName\":\"OpenAI Compatible Model\",\"resourceVersionId\":\"model-ver-1\",\"resourceVersion\":\"1.0.0\",\"providerType\":\"OPENAI_COMPATIBLE\",\"modelId\":\"gpt-test\",\"baseUrl\":\"https://runtime.example\",\"apiKeyEnvVar\":\"TEST_OPENAI_COMPATIBLE_API_KEY\",\"temperature\":0,\"maxTokens\":512,\"enableThinking\":true,\"reasoningEffort\":\"high\"},\"knowledgeBinding\":{\"knowledgeBaseId\":\"kb-1\",\"knowledgeBaseName\":\"Refund Knowledge\",\"knowledgeReleaseId\":\"kr-1\",\"knowledgeReleaseVersion\":\"1.0.0\",\"snapshotId\":\"snapshot-1\",\"defaultTopK\":5,\"retrievalMode\":\"HYBRID\",\"minScore\":0.1},\"skills\":[{\"resourceId\":\"skill-1\",\"resourceName\":\"Refund Skill\",\"resourceVersionId\":\"skill-ver-1\",\"resourceVersion\":\"1.0.0\",\"skillName\":\"Refund Policy\",\"skillDesc\":\"Read refund constraints before answering.\",\"skillPrompt\":\"退款时必须先确认订单状态，再决定是否走退款 playbook。\"}],\"tools\":[{\"resourceId\":\"tool-1\",\"resourceName\":\"Ticket Tool\",\"resourceVersionId\":\"tool-ver-1\",\"resourceVersion\":\"1.0.0\",\"connector\":{\"connectorType\":\"simple-http\",\"accountSnapshot\":null,\"timeoutSeconds\":15,\"retryPolicy\":{\"mode\":\"NONE\",\"maxAttempts\":1,\"initialDelayMs\":0,\"maxDelayMs\":0,\"backoffMultiplier\":1,\"retryableCategories\":[],\"retryableErrorCodes\":[]},\"config\":{\"baseUrl\":\"https://tool.example\"},\"operationMappings\":{\"create_ticket\":{\"method\":\"POST\",\"path\":\"/invoke\",\"requestPlacement\":\"JSON_BODY\"}}},\"operations\":[{\"name\":\"create_ticket\",\"description\":\"Create a service ticket.\",\"inputSchema\":\"{\\\"type\\\": \\\"object\\\", \\\"properties\\\": {\\\"subject\\\": {\\\"type\\\": \\\"string\\\"}}, \\\"required\\\": [\\\"subject\\\"], \\\"additionalProperties\\\": false}\",\"outputSchema\":\"{\\\"type\\\": \\\"object\\\", \\\"properties\\\": {\\\"ticketId\\\": {\\\"type\\\": \\\"string\\\"}, \\\"status\\\": {\\\"type\\\": \\\"string\\\"}}, \\\"required\\\": [\\\"ticketId\\\", \\\"status\\\"], \\\"additionalProperties\\\": false}\"}]}],\"playbooks\":[{\"playbookId\":\"pb-1\",\"name\":\"Playbook 1\",\"description\":\"refund flow\"}]},\"trigger\":{\"id\":\"message-1\",\"senderId\":\"human-1\",\"recipientId\":\"participant-agent-1\",\"taskId\":null,\"replyToMessageId\":null,\"kind\":\"MESSAGE\",\"body\":{\"text\":\"帮我发起退款\"},\"createdAt\":\"2026-10-04T00:00:00Z\"},\"context\":{\"tasks\":[]},\"workMemory\":{\"knownPreference\":\"email\"},\"maxToolSteps\":12}")


def sqlite_transcript_store(path: str | None = None, **settings) -> PostgresTranscriptStore:
    store = PostgresTranscriptStore.__new__(PostgresTranscriptStore)
    store.settings = TranscriptStoreSettings(**settings)
    store.transcript_cache = None
    store.engine = create_engine("sqlite:///" + path if path else "sqlite://",
        connect_args={"check_same_thread": False}, **({} if path else {"poolclass": StaticPool}))
    Base.metadata.create_all(store.engine)
    store.session_factory = sessionmaker(bind=store.engine, autoflush=False, expire_on_commit=False)
    return store


class FakeRedisClient:
    async def ping(self): return True
    async def aclose(self): pass


@contextmanager
def runtime_client(store=None, payload=None):
    from agentyard_agent_runtime.main import app
    from agentyard_agent_runtime.models import ActivationRuntime
    store = store or sqlite_transcript_store()
    # Store is real SQL persistence. Only network dependencies and the trusted API
    # envelope are replaced in unit tests; cross-service E2E uses PostgreSQL/Redis.
    with patch("agentyard_agent_runtime.main.publish_frame", return_value={"accepted": False}), \
         patch("agentyard_agent_runtime.main.create_redis_client", return_value=FakeRedisClient()), \
         patch("agentyard_agent_runtime.main.create_transcript_store", return_value=store), \
         patch.object(store, "initialize"), \
         patch("agentyard_agent_runtime.main.activation_runtime", return_value=ActivationRuntime.model_validate(payload or request_payload())):
        with TestClient(app) as client:
            yield client


def _retry_policy():
    return {"mode": "NONE", "maxAttempts": 1, "initialDelayMs": 0, "maxDelayMs": 0,
        "backoffMultiplier": 1, "retryableCategories": [], "retryableErrorCodes": []}
