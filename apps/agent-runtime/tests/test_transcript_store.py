from dataclasses import replace
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from agentyard_agent_runtime.models import ActivationOutcome
from agentyard_agent_runtime.transcript_store import (
    SessionContextRecord, ToolReceiptRecord, TranscriptEntryRecord, TurnExecutionContext,
    TurnExecutionRecord, TranscriptCachePayload, RedisTranscriptCache,
    transcript_entries_from_provider_messages, transcript_cache_key,
)
from runtime_fixtures import sqlite_transcript_store


def context(activation="activation-1", session="session-1", participant="agent-1", attempt="attempt-1"):
    return TurnExecutionContext(session, participant, "message-" + activation, activation, attempt)


def entries(content, round_id="round-1"):
    return transcript_entries_from_provider_messages(content, model_round_id=round_id)


def commit(store, ctx, messages):
    assert store.begin_execution(ctx) is None
    store.commit_success(ctx, ActivationOutcome(success=True, completion="COMPLETED"), entries(messages, ctx.turn_execution_id))


def test_session_identity_replaces_template_owner_and_epoch():
    store = sqlite_transcript_store()
    ctx = context()
    commit(store, ctx, [{"role": "user", "content": "decision"}])
    other = context(session="session-2", participant="agent-2")
    assert store.load_committed_provider_messages(other) == []
    assert store.load_committed_provider_messages(replace(ctx, turn_execution_id="activation-2"))[0]["content"] == "decision"
    with pytest.raises(ValueError, match="identity"):
        store.history(replace(ctx, participant_id="agent-2"))


def test_duplicate_completed_activation_returns_original_outcome_without_execution():
    store = sqlite_transcript_store()
    ctx = context()
    commit(store, ctx, [{"role": "assistant", "content": "done"}])
    result = store.begin_execution(replace(ctx, execution_attempt_id="retry"))
    assert result.success
    assert len(store.history(ctx)["entries"]) == 1
    with pytest.raises(ValueError, match="identity"):
        store.begin_execution(replace(ctx, turn_id="different-input"))


def test_session_lease_serializes_activations_and_fences_stale_attempts():
    store = sqlite_transcript_store()
    first = context()
    assert store.begin_execution(first) is None
    second = context("activation-2")
    assert not store.begin_execution(second).success
    store.sweep_expired(now=datetime.now(UTC) + timedelta(days=1))
    assert store.begin_execution(second) is None
    with pytest.raises(RuntimeError, match="no longer current"):
        store.commit_success(first, ActivationOutcome(success=True, completion="COMPLETED"), [])
    store.commit_success(second, ActivationOutcome(success=True, completion="COMPLETED"), [])


def test_restart_recovers_checkpoint_and_completed_tool_receipt(tmp_path):
    path = str(tmp_path / "runtime.sqlite")
    first = sqlite_transcript_store(path)
    ctx = context()
    assert first.begin_execution(ctx) is None
    first.append_pending_entries(ctx, entries([{"role": "user", "content": "create a report"}]))
    assert first.begin_tool_call(ctx, "call-1", "write", {"x": 1}, retry_safe=False) is None
    first.complete_tool_call(ctx, "call-1", {"reportId": "report-7"})
    first.close()
    restarted = sqlite_transcript_store(path)
    restarted.sweep_expired(now=datetime.now(UTC) + timedelta(days=1))
    retry = replace(ctx, execution_attempt_id="retry")
    assert restarted.begin_execution(retry) is None
    assert restarted.load_execution_messages(retry)[0]["content"] == "create a report"
    assert restarted.begin_tool_call(retry, "call-1", "write", {"x": 1}, retry_safe=False) == {"reportId": "report-7"}
    restarted.commit_success(retry, ActivationOutcome(success=True, completion="COMPLETED"), [])
    assert restarted.history(retry)["entries"][0]["status"] == "COMMITTED"


def test_uncertain_external_write_is_not_replayed_but_idempotent_server_tool_may_retry():
    store = sqlite_transcript_store()
    ctx = context()
    store.begin_execution(ctx)
    assert store.begin_tool_call(ctx, "external", "write", {}, retry_safe=False) is None
    store.mark_failed(ctx, "connection lost after sending request")
    retry = replace(ctx, execution_attempt_id="retry")
    store.begin_execution(retry)
    result = store.begin_tool_call(retry, "external", "write", {}, retry_safe=False)
    assert result["error"]["code"] == "TOOL_OUTCOME_UNKNOWN"
    assert store.begin_tool_call(retry, "api", "task_create", {}, retry_safe=True) is None
    assert store.begin_tool_call(retry, "api", "task_create", {}, retry_safe=True) is None


def test_tool_call_ids_and_receipts_cannot_change_arguments_or_completed_results():
    store = sqlite_transcript_store()
    ctx = context()
    store.begin_execution(ctx)
    store.begin_tool_call(ctx, "c", "create", {"a": 1}, retry_safe=False)
    with pytest.raises(ValueError, match="different arguments"):
        store.begin_tool_call(ctx, "c", "create", {"a": 2}, retry_safe=False)
    store.complete_tool_call(ctx, "c", {"id": "one"})
    with pytest.raises(ValueError, match="overwritten"):
        store.complete_tool_call(ctx, "c", {"id": "two"})


def test_history_and_receipts_survive_retention_sweep_after_century():
    store = sqlite_transcript_store()
    ctx = context()
    store.begin_execution(ctx)
    store.begin_tool_call(ctx, "c", "write", {}, retry_safe=False)
    store.complete_tool_call(ctx, "c", {"id": 1})
    store.commit_success(ctx, ActivationOutcome(success=True, completion="COMPLETED"), entries([{"role": "user", "content": "ongoing duty"}]))
    result = store.sweep_expired(now=datetime.now(UTC) + timedelta(days=36500))
    assert result["transcriptEntriesDeleted"] == 0
    assert store.history(ctx)["entries"][0]["message"]["content"] == "ongoing duty"
    with store.session_factory() as session:
        assert len(session.scalars(select(ToolReceiptRecord)).all()) == 1


def test_history_paginates_and_search_is_literal():
    store = sqlite_transcript_store()
    ctx = context()
    commit(store, ctx, [{"role": "user", "content": s} for s in ["decision 10%", "waiting", "report"]])
    page = store.history(ctx, limit=2)
    assert page["nextCursor"] == 2
    assert store.history(ctx, after=2)["entries"][0]["message"]["content"] == "report"
    assert len(store.history(ctx, query="%")["entries"]) == 1
    assert store.history(ctx, query="not present")["entries"] == []


def test_context_window_preserves_complete_tool_exchange_boundaries():
    store = sqlite_transcript_store(context_window_messages=4)
    ctx = context()
    commit(store, ctx, [
        {"role": "user", "content": "old request"},
        {"role": "assistant", "content": "", "tool_calls": [{"id": "old", "function": {"name": "read", "arguments": "{}"}}]},
        {"role": "tool", "tool_call_id": "old", "content": "{}"},
        {"role": "user", "content": "current request"},
        {"role": "assistant", "content": "current answer"},
    ])
    window = store.load_committed_provider_messages(ctx)
    assert "durably stored" in window[0]["content"]
    assert [m["content"] for m in window[1:]] == ["current request", "current answer"]
    assert len(store.history(ctx)["entries"]) == 5


def test_working_summary_validates_version_and_sources_and_survives_restart(tmp_path):
    path = str(tmp_path / "memory.sqlite")
    store = sqlite_transcript_store(path)
    ctx = context()
    store.begin_execution(ctx)
    store.append_pending_entries(ctx, entries([{"role": "user", "content": "follow up tomorrow"}]))
    memory = store.write_memory(ctx, expected_version=0, source_through_seq=1,
        content={"waiting": ["message-activation-1"], "decisions": ["follow up tomorrow"]})
    assert memory["version"] == 1
    with pytest.raises(ValueError, match="revision conflict"):
        store.write_memory(ctx, expected_version=0, source_through_seq=1, content={})
    with pytest.raises(ValueError, match="source range"):
        store.write_memory(ctx, expected_version=1, source_through_seq=100, content={})
    store.commit_success(ctx, ActivationOutcome(success=True, completion="COMPLETED"), [])
    store.close()
    restored = sqlite_transcript_store(path)
    assert restored.memory(ctx)["content"]["waiting"] == ["message-activation-1"]
    assert "follow up tomorrow" in restored.load_committed_provider_messages(ctx)[0]["content"]


def test_checkpoint_replay_is_idempotent_and_cannot_rewrite_history():
    store = sqlite_transcript_store()
    ctx = context()
    store.begin_execution(ctx)
    message = entries([{"role": "user", "content": "original"}])
    store.append_pending_entries(ctx, message)
    store.append_pending_entries(ctx, message)
    assert len(store.history(ctx)["entries"]) == 1
    with pytest.raises(ValueError, match="differs"):
        store.append_pending_entries(ctx, entries([{"role": "user", "content": "edited"}]))


def test_tool_run_receipts_do_not_take_over_running_agent_activation():
    store = sqlite_transcript_store()
    agent = context()
    node = context("tool-run:node:1", attempt="tool-run:node:1")
    store.begin_execution(agent)
    assert store.begin_tool_call(node, "node", "create", {}, retry_safe=False, require_activation=False) is None
    store.complete_tool_call(node, "node", {"statePatch": {"done": True}}, require_activation=False)
    store.heartbeat(agent)
    assert store.begin_tool_call(node, "node", "create", {}, retry_safe=False, require_activation=False)["statePatch"]["done"]


def test_failed_cache_invalidation_cannot_hide_committed_history():
    class StaleCache:
        payload = None
        def get(self, *_): return self.payload
        def put(self, _, payload): self.payload = payload
        def delete(self, _): pass
        def close(self): pass
    store = sqlite_transcript_store()
    store.transcript_cache = StaleCache()
    first = context()
    commit(store, first, [{"role": "user", "content": "first"}])
    assert len(store.load_committed_provider_messages(first)) == 1
    second = context("activation-2")
    commit(store, second, [{"role": "user", "content": "second"}])
    assert len(store.load_committed_provider_messages(second)) == 2


def test_cache_identity_cannot_cross_participants():
    class Redis:
        def get(self, _): return '{"sessionId":"session-1","participantId":"other","providerType":"OPENAI_COMPATIBLE","lastCommittedSeq":1,"messages":[]}'
    assert RedisTranscriptCache(Redis(), 10).get(context(), "OPENAI_COMPATIBLE") is None
    assert transcript_cache_key(context()) == "agent-runtime:transcript:session-1"
