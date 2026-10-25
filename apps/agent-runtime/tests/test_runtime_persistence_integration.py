"""Opt-in PostgreSQL + Redis integration; creates and drops only a random test database."""
import os
import uuid
from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

from agentyard_agent_runtime.models import ActivationOutcome, LlmUsageEntry
from agentyard_agent_runtime.redis_support import RedisSettings, create_sync_redis_client
from agentyard_agent_runtime.transcript_store import (
    PostgresTranscriptStore, TranscriptStoreSettings, TurnExecutionContext,
    create_transcript_cache, transcript_cache_key, transcript_entries_from_provider_messages,
)


@pytest.mark.skipif(os.getenv("AGENTYARD_RUNTIME_INTEGRATION") != "1", reason="requires explicitly enabled local PostgreSQL and Redis")
def test_actual_postgres_and_redis_recover_same_instance_without_losing_receipts():
    settings = TranscriptStoreSettings.from_env()
    database_name = "agentyard_runtime_test_" + uuid.uuid4().hex
    source_url = make_url(settings.database_url)
    admin = create_engine(source_url.set(database="postgres"), isolation_level="AUTOCOMMIT")
    redis_settings = RedisSettings.from_env()
    redis = create_sync_redis_client(redis_settings)
    session_id = "integration-" + uuid.uuid4().hex
    context = TurnExecutionContext(session_id, "participant-" + session_id, "input", "activation", "first")
    stores = []
    created = False
    try:
        assert redis.ping()
        with admin.connect() as connection:
            connection.execute(text('CREATE DATABASE "' + database_name + '"'))
        created = True
        test_settings = replace(settings, database_url=source_url.set(database=database_name).render_as_string(hide_password=False))
        store = PostgresTranscriptStore(test_settings, transcript_cache=create_transcript_cache(redis_settings, test_settings))
        stores.append(store)
        store.initialize()
        assert store.check_database()["backend"] == "postgresql"
        store.begin_execution(context)
        store.append_pending_entries(context, transcript_entries_from_provider_messages(
            [{"role": "user", "content": "Keep following supplier delivery through next month."}], model_round_id="input"))
        store.begin_tool_call(context, "call-1", "supplier_update", {"supplierId": "supplier-7"}, retry_safe=False)
        store.complete_tool_call(context, "call-1", {"receipt": "external-123"})
        usage = LlmUsageEntry(sourceType="AGENT_MODEL", callSequence=1, providerType="OPENAI_COMPATIBLE",
            modelId="integration", usageAvailable=True, totalTokens=42, occurredAt=datetime.now(UTC).isoformat())
        store.checkpoint_usage(context, [usage])
        store.begin_tool_call(context, "unresolved", "another_external_write", {"item": "original"}, retry_safe=False)
        store.write_memory(context, expected_version=0, source_through_seq=1,
            content={"openWork": ["supplier-7"], "sourceMessageId": "input"})
        # Simulate process loss before activation completion; SQL and Redis remain live.
        store.close()
        restarted = PostgresTranscriptStore(test_settings, transcript_cache=create_transcript_cache(redis_settings, test_settings))
        stores.append(restarted)
        restarted.initialize()
        restarted.sweep_expired(now=datetime.now(UTC) + timedelta(days=1))
        retry = replace(context, execution_attempt_id="restarted")
        assert restarted.begin_execution(retry) is None
        assert restarted.execution_usage(retry) == [usage.model_dump(mode="json")]
        assert restarted.begin_tool_call(retry, "new-call-id", "another_external_write", {"item": "renamed"},
            retry_safe=False)["error"]["code"] == "TOOL_OUTCOME_UNKNOWN"
        assert restarted.begin_tool_call(retry, "call-1", "supplier_update", {"supplierId": "supplier-7"},
            retry_safe=False) == {"receipt": "external-123"}
        assert restarted.memory(retry)["content"]["openWork"] == ["supplier-7"]
        restarted.commit_success(retry, ActivationOutcome(success=True, completion="COMPLETED", llmUsage=[usage]), [])
        assert len(restarted.load_committed_provider_messages(retry)) == 2
        assert redis.exists(transcript_cache_key(retry))
        redis.delete(transcript_cache_key(retry))
        assert len(restarted.load_committed_provider_messages(retry)) == 2
        assert restarted.sweep_expired(now=datetime.now(UTC) + timedelta(days=36500))["transcriptEntriesDeleted"] == 0
        assert restarted.history(retry)["entries"][0]["message"]["content"].startswith("Keep following")
    finally:
        for store in stores:
            store.close()
        redis.delete(transcript_cache_key(context))
        redis.close()
        if created:
            with admin.connect() as connection:
                connection.execute(text('DROP DATABASE "' + database_name + '" WITH (FORCE)'))
        admin.dispose()
