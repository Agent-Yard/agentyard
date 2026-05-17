# Messages Refactor Workflow And Agent Runtime

## 1. Workflow Contract

```java
public record UserTurn(
    String turnId,
    String customerId,
    String turnDedupKey,
    List<SessionMessage> messages,
    Map<String, Object> metadata
) {
}

public record SessionTrigger(
    SessionTriggerType triggerType,
    String turnId,
    String eventId,
    Map<String, Object> payload
) {
}
```

Rules:

- `SessionStartRequest` is workflow bootstrap input for a session row that API has already created
- `sessionId` is both the workflow id and the authoritative `session_runtime_session.id`
- workflow `run()` initializes in-memory state from `SessionStartRequest`, then updates the existing session projection
- workflow persistence activity must not insert the initial session row; missing row is an invariant violation
- workflow projection update must preserve API-owned active identity fields and `next_message_sequence`
- `messages` contains API-accepted, already persisted `SessionMessage` rows
- workflow never assigns external input message ids
- workflow never deduplicates `externalMessageId`
- workflow appends accepted external messages to its in-memory state from the persisted delta
- `submitUserTurn` handles the whole `messages[]` once

API starts or ensures the workflow after the session row transaction commits. Starting the same workflow id twice is treated as idempotent success when Temporal reports it is already running.

## 2. Workflow Idempotency

API submits Temporal update with `updateId = turnId`.

Workflow state keeps an accepted/processed turn registry:

```java
Map<String, UserTurnAcceptedResult> processedTurnsById;
```

Accepted-before guard:

- workflow-local `draining / ended / agentTurnActive` races must be rejected before Temporal accepted stage, using `@UpdateValidatorMethod` on the `submitUserTurn` update
- these guard rejections are not normal workflow update results and must not be recorded as `WORKFLOW_ACCEPTED`
- after accepted stage, `submitUserTurn` must not return business `BUSY / REJECTED`

On accepted `submitUserTurn` execution:

1. If `turnId` exists, return stored accepted/idempotent result
2. Append persisted message delta to in-memory message list
3. If human handoff active, mark accepted without agent execution
4. Execute owner turn cycle
5. Store accepted/idempotent result by `turnId`

Model failure, tool failure, decision rejection and security block are accepted turn outcomes. They emit platform events/messages as needed and do not change the accepted turn into `BUSY / REJECTED`.

## 3. Turn Execution Ids

One `turnId` can produce multiple model executions when owner switch occurs.

Rules:

- `turnExecutionId = {turnId}:exec-{n}`
- `replyMessageId` is allocated before each model execution
- agent reply, security block reply, failed reply and decision rejected reply reuse the parent `turnId`
- platform messages are appended through persistence and returned as `SessionMessage`

## 4. Platform Turns

Platform actions without external input need explicit platform turns:

- human operator proactive reply
- playbook completed re-evaluation
- external callback that should wake owner agent
- human resume that should wake owner agent
- system event that should trigger owner agent

Platform turn rules:

- `accepted_input_message_ids = []`
- `producer_type = PLATFORM` for platform-created visible messages
- agent reply belongs to this platform turn
- platform turn can start with `message_ids = []`; later operator/system/agent visible messages append into the same turn
- platform turn allocator creates or reuses only the `session_runtime_turn` row; it does not append messages or call Temporal/agent-runtime
- dedup key is derived from the source:
  - human operator proactive reply: operator action id
  - external callback: callback idempotency key
  - human resume: resume event id
  - playbook completed re-evaluation: playbook progress id or session event id
  - system owner wakeup: session event id
- if a platform action has no natural dedup source, create a session event first and derive the turn dedup key from that event id
- API-originated platform actions allocate the platform turn before signalling/updating workflow
- workflow-originated platform actions allocate the platform turn through a persistence activity before owner execution
- same platform dedup key replay returns the same `turnId` and must not duplicate owner agent execution

## 5. Agent Runtime Contract

```java
public record AgentTurnRequest(
    String sessionId,
    String turnId,
    String turnExecutionId,
    String replyMessageId,
    long ownershipEpoch,
    String assistantId,
    String assistantReleaseVersion,
    AgentConfig currentOwner,
    List<AgentConfig> availableAgents,
    List<PlaybookConfig> availablePlaybooks,
    ActivePlaybookSummary activePlaybook,
    Map<String, Object> sharedState,
    LlmModelDescriptor effectivePrivacyModelBinding,
    boolean effectivePrivacyMappingEnabled,
    SessionTrigger trigger,
    List<SessionMessage> messages,
    List<AgentRuntimeContextEntry> contextEntries,
    boolean transcriptBootstrap
) {
}
```

`AgentRuntimeContextEntry`:

```java
public record AgentRuntimeContextEntry(
    String entryId,
    AgentRuntimeContextEntryType entryType,
    long revision,
    Instant occurredAt,
    Map<String, Object> data
) {
}

public enum AgentRuntimeContextEntryType {
    SESSION_EVENT,
    SHARED_STATE_SNAPSHOT,
    SHARED_STATE_PATCH,
    ACTIVE_PLAYBOOK_SUMMARY
}
```

## 6. Context Entry Generation

Workflow is responsible for generating `contextEntries`. Agent-runtime must not infer session events, shared state changes, playbook state, or owner-switch context by reading session-runtime data directly.

Ordering and idempotency:

- `messages` are ordered by persisted session `sequence`.
- `contextEntries` are ordered by `(occurredAt, revision, entryId)`.
- The tuple `(entryType, entryId, revision)` is the idempotency key for rendered context transcript entries.
- Retrying the same `turnExecutionId` must produce the same `messages`, `contextEntries`, ordering, and `transcriptBootstrap` flag unless the previous attempt never reached the accepted execution boundary.

### SESSION_EVENT

Generated for session events that should influence model decisions.

Rules:

- Use session event id as `entryId`.
- Use session event sequence as `revision`.
- Use event created time as `occurredAt`.
- `data` preserves actor, event type, related message/playbook/owner ids, and payload.
- Include events such as human resume, external callback, human handoff start/end, owner switch, system owner wakeup, and playbook wait/resume/complete.
- Pure audit events that should not influence model behavior do not need context entries.

### SHARED_STATE_PATCH

Generated when a tool/model turn changes shared state and the changed keys are known.

Rules:

- `session_runtime_session.shared_state_revision` is the canonical monotonic revision source.
- Increment `shared_state_revision` only when authoritative `shared_state` changes.
- Use `entryId = shared-state-patch:{sessionId}:{sharedStateRevision}`.
- Use current shared state revision as `revision`.
- `data` contains changed keys and new values, or a structured patch.
- Normal turns should prefer patch entries instead of rendering the full shared state snapshot every time.

### SHARED_STATE_SNAPSHOT

Generated when patch entries are not enough to rebuild the current owner/provider context.

Rules:

- Generate on transcript bootstrap, owner switch, missing patch history, retention boundary, size boundary, provider context rebuild, or explicit rebuild.
- Use `entryId = shared-state-snapshot:{sessionId}:{sharedStateRevision}`.
- Use current shared state revision as `revision`.
- `data` contains the authoritative snapshot or a bounded snapshot with explicit truncation metadata.
- Agent-runtime uses this entry for prompt rendering only; `sharedState` in `AgentTurnRequest` remains the authoritative runtime/tool/policy value.

### ACTIVE_PLAYBOOK_SUMMARY

Generated when active playbook state changes in a way that should affect the next model decision.

Rules:

- Generate on playbook start, wait, resume, complete, failure, cancellation, or node/state change.
- Use an id that combines playbook run id and the state revision or related session event id.
- Use related session event sequence or playbook run updated revision as `revision`.
- `data` includes playbook id, run id, owner agent id, status, current node/key waiting reason, last result, and failure reason when present.

### Owner Switch Context

Owner switch must preserve why the new owner is taking over.

Rules:

- Generate a `SESSION_EVENT` for the owner switch reason.
- Generate `SHARED_STATE_SNAPSHOT` when the new owner cannot rely on prior patch history.
- Generate `ACTIVE_PLAYBOOK_SUMMARY` when an active playbook is still relevant to the new owner.

## 7. Bootstrap Rules

Set `transcriptBootstrap = true` when:

- committed transcript is missing for the current owner/provider context
- owner agent or ownership epoch changes
- provider type changes
- transcript store reports committed chain/cache invalid
- patch/event history needed for reconstruction is missing or beyond retention
- explicit rebuild is requested

Bootstrap request contents:

- persisted session messages needed to rebuild the current owner/provider context, ordered by session `sequence`
- current turn `messages`
- `SESSION_EVENT` entries needed to explain recent triggers and ownership/playbook changes
- `SHARED_STATE_SNAPSHOT` at current `shared_state_revision`
- `ACTIVE_PLAYBOOK_SUMMARY` for the current active playbook, if any
- platform trigger context for no-user platform turns

Bootstrap is a rebuild of the current owner/provider transcript. It is not limited to the current user delta.

## 8. Prompt Rendering

agent-runtime prompt source:

- committed provider transcript replay from transcript store
- current request `messages`
- current request `contextEntries`

Runtime context tools:

- Remove the current `list_recent_events` tool, or replace it with a bounded `list_context_entries` tool backed only by current request `contextEntries`.
- Do not keep any tool that depends on `recentEvents`, `recent_messages`, or direct session-runtime lookup.
- `get_shared_state` may continue reading the authoritative `sharedState` request field for tool/policy use, but provider transcript rendering of shared state still comes from `contextEntries`.

Normal turn:

- Load committed transcript
- Render and append only current delta
- Commit current delta + model/tool output on success

Bootstrap turn:

- Begin execution
- Resolve provider type
- Reset committed transcript for current owner context and provider
- Load committed transcript, expected empty after reset
- Render bootstrap `messages + contextEntries`
- Commit bootstrap entries + model/tool output on success

## 9. Security Scope

Security user-message assessment applies to all current delta messages where `role = USER`.

Rules:

- If current turn has multiple user messages, assess all of them as the current user input surface
- Imported `ASSISTANT/HUMAN_OPERATOR/SYSTEM` messages enter transcript by role but are not assessed as user attack input
- If a turn has no user messages, skip user-message attack assessment but still apply general runtime/tool safety policies

## 10. Transcript Store Reset

Bootstrap reset requires a durable marker so retries do not delete transcript entries rebuilt by an earlier attempt.

Reset marker schema:

```sql
create table transcript_bootstrap_reset (
    turn_execution_id varchar(128) not null,
    provider_type varchar(64) not null,
    session_id varchar(128) not null,
    owner_agent_id varchar(128) not null,
    ownership_epoch integer not null,
    execution_attempt_id varchar(128) not null,
    status varchar(32) not null,
    deleted_committed_entry_count integer not null default 0,
    reset_started_at timestamp with time zone not null,
    reset_completed_at timestamp with time zone,
    failure_reason text,
    primary key (turn_execution_id, provider_type)
);
```

Reset marker statuses:

- `COMPLETED`: committed transcript deletion for this `turnExecutionId/providerType` has already completed.
- `FAILED`: reset attempted but failed before completion; retry may attempt reset again after locking the current execution.

Transcript store bootstrap reset order:

1. `begin_execution(context)`
2. If cached success exists, return `FINAL_OUTCOME` and do not reset/load/commit
3. If `transcriptBootstrap = true`, call `reset_committed_provider_transcript_for_bootstrap(context, providerType)`
4. Reset locks `turn_execution` with `for update`
5. If execution already `SUCCEEDED`, reset is forbidden and must not delete committed entries
6. Reset verifies `current_execution_attempt_id == context.executionAttemptId`
7. If a `COMPLETED` reset marker exists for `(turnExecutionId, providerType)`, skip committed transcript deletion
8. If no completed marker exists, delete committed transcript entries for `(sessionId, ownerAgentId, ownershipEpoch, providerType)` and record a `COMPLETED` marker in the same DB transaction
9. Reset invalidates transcript cache for `(sessionId, ownerAgentId, ownershipEpoch, providerType)` on every reset call, including completed-marker retries
10. `load_committed_provider_messages(context, providerType)`
11. `commit_success(context, outcome, entries)`
12. `mark_failed` only aborts current pending entries, never deletes committed transcript and never removes a completed reset marker

Retry rules:

- Retry after reset completed but before commit reuses the completed marker, reloads an empty committed transcript, rebuilds, and commits once.
- Retry after successful commit returns cached/final outcome from `begin_execution` and does not reset.
- Retry after failed execution may reuse the completed reset marker but must not delete committed transcript again.
- Provider types are isolated: reset for one provider type must not delete committed transcript for another provider type.

## 11. Stream Payload Rename

Turn-level frame payloads use `replyMessageId` in JVM contracts, Python models, API stream services, channel relay, and Web client types. Do not keep `messageId` as an outward stream alias for compatibility; in stream frames, the id being carried is the platform reply message id, not the trigger/input message id.

```java
public record TurnStartedPayload(
    String replyMessageId,
    SessionTriggerType triggerType,
    int inputMessageCount
) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
}

public record TurnCompletedPayload(
    String replyMessageId,
    TurnCompletionStatus status
) implements AgentTurnTransientPayload {
}

public record ErrorPayload(
    String code,
    String replyMessageId,
    String message,
    StreamErrorStage stage,
    boolean retryable,
    Map<String, Object> details
) implements AgentTurnStreamPayload, AgentTurnTransientPayload {
}
```

`REPLY_BLOCK_DELTA`、`REPLY_BLOCK_COMPLETED`、`FINAL_OUTCOME` also bind to `replyMessageId`.

Web draft reply state must key streamed assistant/operator/system drafts by `(turnId, replyMessageId)`. Persisted user/input drafts are reconciled from `acceptedMessageAllocations`, not from stream frames.
