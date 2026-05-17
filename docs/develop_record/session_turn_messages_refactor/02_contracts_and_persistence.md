# Messages Refactor Contracts And Persistence

## 1. API Boundary

### Web Public API

```http
POST /api/session-runtime/turns
Idempotency-Key: {turnDedupKey}
```

```ts
export interface SendSessionTurnRequest {
  sessionId?: string | null;
  assistantId?: string | null;
  customerId: string;
  turnDedupKey: string;
  messages: WebSessionTurnMessageInput[];
  metadata: Record<string, unknown>;
}

export interface WebSessionTurnMessageInput {
  clientMessageId?: string | null;
  occurredAt?: string | null;
  blocks: SessionMessageBlock[];
  metadata: Record<string, unknown>;
}

export interface SendSessionTurnResponse {
  sessionId: string;
  turnId: string;
  status: SessionMessageDeliveryStatus;
  acceptedMessageIds: string[];
  acceptedMessageAllocations: AcceptedSessionMessageAllocation[];
  duplicateExternalMessageIds: string[];
  reason: string | null;
}

export interface AcceptedSessionMessageAllocation {
  requestIndex: number;
  clientMessageId?: string | null;
  messageId: string;
  turnIndex: number;
}
```

Rules:

- `sessionId` 有值时追加到指定 session
- `sessionId` 为空时 `assistantId` 必填，并按 Web active identity 创建或复用 session
- Web 入口强制 `role = USER`、`sender = CUSTOMER`
- `turnDedupKey` 必须等于 `Idempotency-Key`
- `clientMessageId` 只用于前端临时关联，不作为数据库主键
- `acceptedMessageIds` 是 `acceptedMessageAllocations[].messageId` 的简单列表，保留给只关心 accepted id 的消费者
- Web draft reconciliation 必须使用 `acceptedMessageAllocations` 显式映射 `requestIndex/clientMessageId -> messageId/turnIndex`，不能依赖数组猜测或 stream 回填
- `SendSessionTurnResponse` 只表示 turn 已被 API 接受或幂等复用；正常 preflight rejection 使用 HTTP error，不返回带 `turnId` 的 accepted response
- `SessionMessageDeliveryStatus.BUSY / REJECTED` 不再是 workflow accepted 后的普通业务结果；这些拒绝必须发生在 turn accepted boundary 之前

### Channel/Internal Turn API

```java
public record ChannelInboundSessionTurnRequest(
    String channelProfileId,
    String externalConversationId,
    String dedupKey,
    String assistantId,
    String customerId,
    String sessionId,
    List<ChannelInboundSessionTurnMessage> messages,
    Map<String, Object> metadata
) {
}

public record ChannelInboundSessionTurnMessage(
    String externalEventId,
    String externalMessageId,
    Instant occurredAt,
    SessionMessageRole role,
    SessionMessageSender sender,
    SessionMessageInput message
) {
}
```

Rules:

- `messages[].externalMessageId` 必填
- message-level `sender` 必填，gateway 负责 turn-level fallback
- gateway 负责 provider event matrix、附件转换、binding 解析；API 不理解 provider event type
- 所有 channel inbound turn messages 写入 session 时都是 `producer_type = EXTERNAL`
- API 使用 `channelProfileId + externalConversationId + customerId + assistantId` 创建或复用 channel active session，不得退化为 `customerId + assistantId`

### Trusted Import Turn API

```http
POST /internal/session-runtime/import-turns
Idempotency-Key: {turnDedupKey}
```

```java
public record TrustedImportSessionTurnRequest(
    ImportSessionTarget target,
    String turnDedupKey,
    String importBatchId,
    String sourceSystem,
    List<TrustedImportSessionTurnMessage> messages,
    Map<String, Object> metadata
) {
}

public sealed interface ImportSessionTarget permits ExistingSessionImportTarget, WebIdentityImportTarget, ChannelIdentityImportTarget {
}

public record ExistingSessionImportTarget(
    String sessionId,
    String customerId,
    String assistantId
) implements ImportSessionTarget {
}

public record WebIdentityImportTarget(
    String customerId,
    String assistantId
) implements ImportSessionTarget {
}

public record ChannelIdentityImportTarget(
    String channelProfileId,
    String externalConversationId,
    String customerId,
    String assistantId
) implements ImportSessionTarget {
}

public record TrustedImportSessionTurnMessage(
    String importMessageId,
    String externalMessageId,
    Instant occurredAt,
    SessionMessageRole role,
    SessionMessageSender sender,
    SessionMessageInput message,
    Map<String, Object> metadata
) {
}
```

Rules:

- This API is trusted/internal only; Web/public clients must never be able to call it.
- `turnDedupKey` must equal `Idempotency-Key`; `importBatchId` should be the source import batch id and may be used to derive `turnDedupKey`.
- Import is a write source, not a new session entry scope. `target` must resolve to an existing session, Web active identity, or Channel active identity.
- `ExistingSessionImportTarget` must validate `customerId/assistantId` against the target session.
- `WebIdentityImportTarget` uses Web active identity create-or-reuse.
- `ChannelIdentityImportTarget` uses Channel active identity create-or-reuse and must preserve `channelProfileId + externalConversationId`.
- Trusted import messages may use `USER / ASSISTANT / HUMAN_OPERATOR / SYSTEM` roles and must include a server-trusted `sender`.
- `importMessageId` is required for source-level idempotency when `externalMessageId` is absent. At least one of `importMessageId` or `externalMessageId` is required per message.
- API derives a canonical source message id for message-level dedupe:
  - use `externalMessageId` when present
  - otherwise use `{sourceSystem}:{importMessageId}`
- The canonical source message id is persisted as `session_runtime_message.external_message_id`; raw `importMessageId/sourceSystem/importBatchId` are preserved in message metadata.
- All imported messages are written with `producer_type = EXTERNAL`.
- Imported `ASSISTANT/HUMAN_OPERATOR/SYSTEM` messages are visible in transcript replay by role, but they must not create channel `FINAL_DELIVERY`.

## 2. Session Message Projection

```java
public enum SessionMessageProducerType {
    EXTERNAL,
    PLATFORM
}

public record SessionMessage(
    String messageId,
    String sessionId,
    long sequence,
    String turnId,
    int turnIndex,
    SessionMessageProducerType producerType,
    String externalMessageId,
    String clientMessageId,
    Instant occurredAt,
    SessionMessageRole role,
    SessionMessageSender sender,
    SessionMessageStatus status,
    List<Object> blocks,
    Map<String, Object> metadata,
    String relatedPlaybookRunId,
    String relatedOwnerAgentId,
    String sourceEventId,
    Instant createdAt,
    Instant updatedAt
) {
}
```

Rules:

- `occurredAt` 是外部消息真实发生时间；`createdAt` 是平台落库时间
- 所有 session messages 必须有非空 `turnId/turnIndex/producerType`
- `turnIndex` 是同一 turn 内展示和审计顺序
- 同一 turn 内 duplicate input 不落 session message，因此 accepted messages 的 `turnIndex` 使用紧凑连续编号
- 原始 request index 放在 message metadata 或 turn allocation JSON 中，用于排障

## 3. Session Schema

```sql
alter table session_runtime_session
    add column entry_scope varchar(32) not null,
    add column channel_profile_id varchar(64),
    add column external_conversation_id varchar(255),
    add column next_message_sequence bigint not null default 1,
    add column shared_state_revision bigint not null default 0;

alter table session_runtime_session
    add constraint ck_session_runtime_session_entry_scope
    check (entry_scope in ('WEB', 'CHANNEL'));

alter table session_runtime_session
    add constraint ck_session_runtime_session_channel_identity
    check (
        (entry_scope = 'WEB' and channel_profile_id is null and external_conversation_id is null)
        or
        (entry_scope = 'CHANNEL' and channel_profile_id is not null and external_conversation_id is not null)
    );

create unique index uk_session_runtime_active_web
    on session_runtime_session (customer_id, assistant_id)
    where entry_scope = 'WEB' and status <> 'ENDED';

create unique index uk_session_runtime_active_channel
    on session_runtime_session (channel_profile_id, external_conversation_id, customer_id, assistant_id)
    where entry_scope = 'CHANNEL' and status <> 'ENDED';
```

## 4. API-Owned Session Creation

`session_runtime_session` 的首行创建权属于 API，而不是 worker。

Active identity:

- Web: `entry_scope = WEB`, `customer_id`, `assistant_id`
- Channel: `entry_scope = CHANNEL`, `channel_profile_id`, `external_conversation_id`, `customer_id`, `assistant_id`
- `session_id` 同时作为 DB session id 和 Temporal workflow id

Create-or-reuse rules:

1. API 在 start workflow 前构造 active identity。
2. 如果请求显式带 `sessionId`，API 必须加载该 session 并校验 scope 和 identity 字段匹配请求上下文。
3. 如果请求不带 `sessionId`，API 通过 active identity 在 DB 事务中创建或复用非 `ENDED` session row。
4. 并发插入依赖 partial unique index 做最终仲裁；唯一冲突时读取已存在 active session 并复用，不重新分配 session id。
5. Redis identity/session lock 只能作为减少并发重试的优化，不能作为正确性条件。
6. 如果复用到的 active session 对应 workflow 已关闭，API 先把旧 row 标记为 `ENDED`，再重新按 active identity 创建或复用新 session。显式 `sessionId` 请求命中已关闭 workflow 时按 ended/closed session 处理，不静默换到另一个 session。
7. Temporal describe/start 等外部 IO 不得放在 append transaction 内。

Initial API-created row must include enough workflow-visible projection data for list/detail/read-after-write:

- `id`
- `scenario_id`
- `title`
- `entry_scope`
- `channel_profile_id`
- `external_conversation_id`
- `customer_id`
- `assistant_id`
- `assistant_name`
- `assistant_release_version`
- `status = IDLE`
- `primary_agent_id`
- `current_owner_agent_id`
- `active_playbook_run_id = null`
- `agent_turn_active = false`
- `session_human_handoff_active = false`
- `pending_owner_reevaluation = false`
- `draining = false`
- `shared_state`
- `shared_state_revision = 0`
- `idle_deadline`
- `next_message_sequence = 1`
- `created_at / updated_at`
- `ended_at = null`

Workflow start rules:

- API starts or ensures the workflow only after the session row transaction commits.
- `WorkflowExecutionAlreadyStarted` for the same `sessionId` is success.
- If API crashes after session row commit but before workflow start, retry finds the same session row and runs ensure-start before submitting `submitUserTurn`.
- `SessionStartRequest` is workflow bootstrap input only; it must not be treated as permission for worker to insert the authoritative session row.

Worker projection update rules:

- Worker persistence activity updates the existing `session_runtime_session` row by `sessionId`.
- Missing row is an invariant violation and should fail/retry visibly.
- Worker may update projection fields such as `status/current_owner_agent_id/active_playbook_run_id/agent_turn_active/session_human_handoff_active/pending_owner_reevaluation/draining/shared_state/shared_state_revision/idle_deadline/updated_at/ended_at`.
- Worker must not overwrite `entry_scope/channel_profile_id/external_conversation_id/customer_id/assistant_id/created_at/next_message_sequence`.
- `next_message_sequence` is advanced only by the unified append transaction.

## 5. Turn Schema

```sql
create table session_runtime_turn (
    turn_id varchar(64) primary key,
    session_id varchar(64) not null,
    dedup_key varchar(128) not null,
    trigger_type varchar(64) not null,
    status varchar(32) not null,
    input_allocations jsonb not null,
    accepted_input_message_ids jsonb not null,
    duplicate_external_message_ids jsonb not null,
    message_ids jsonb not null,
    temporal_update_id varchar(128),
    metadata jsonb not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create unique index uk_session_runtime_turn_dedup
    on session_runtime_turn (session_id, dedup_key);
```

`input_allocations` stores stable allocation data for recovery:

```json
[
  {
    "requestIndex": 0,
    "messageId": "session-message-1",
    "externalMessageId": "external-1",
    "clientMessageId": "client-1"
  }
]
```

Statuses:

- `ALLOCATED_IDS`：已分配并持久化 `turnId/messageId`
- `MESSAGES_APPENDED`：已 append 本次新接受的外部输入消息
- `WORKFLOW_ACCEPTED`：Temporal update 已到 accepted stage
- `REJECTED`：进入幂等边界后、append 前被 accepted-before guard 确定拒绝，且没有部分消息写入
- `FAILED`：进入幂等边界后的恢复性失败，允许后续补偿或重试；如果消息已 append 但 Temporal 未 accepted，重试必须复用同一 allocation，不得重新 append

Preflight rejection does not create a turn row:

- 空 `messages[]`
- 参数校验失败
- target session mismatch
- active identity mismatch
- session busy/draining/ended

## 6. Message Schema

```sql
alter table session_runtime_message
    add column turn_id varchar(64) not null,
    add column turn_index integer not null,
    add column producer_type varchar(32) not null,
    add column external_message_id varchar(255),
    add column client_message_id varchar(255),
    add column occurred_at timestamp with time zone;

alter table session_runtime_message
    add constraint ck_session_runtime_message_turn_index_nonnegative
    check (turn_index >= 0);

alter table session_runtime_message
    add constraint ck_session_runtime_message_producer_type
    check (producer_type in ('EXTERNAL', 'PLATFORM'));

create unique index uk_session_runtime_message_turn_index
    on session_runtime_message (session_id, turn_id, turn_index);

create unique index uk_session_runtime_message_external
    on session_runtime_message (session_id, external_message_id)
    where external_message_id is not null;

create index idx_session_runtime_message_turn
    on session_runtime_message (session_id, turn_id, turn_index);

create index idx_session_runtime_message_outbound
    on session_runtime_message (producer_type, role, status, final_sequence);
```

## 7. Unified Append

`appendSessionMessages(sessionId, turnId, messages)` is the only session message write path.

This is a persistence primitive. It assumes the caller already has a valid `turnId`; it does not create platform turns, start workflows, submit Temporal updates, or call agent-runtime/channel providers. Caller migration is handled by later execution tasks.

It must:

1. Lock `session_runtime_session` row with `for update`
2. Lock or read `session_runtime_turn`
3. Determine current max `turnIndex` for `(sessionId, turnId)`
4. Allocate continuous session `sequence` from `next_message_sequence`
5. Insert all messages
6. Append message ids to `session_runtime_turn.message_ids`
7. Return persisted `SessionMessage` rows

No external IO is allowed inside this transaction.

## 8. Recovery Order

On send-turn:

1. Build active identity; acquire optional Redis identity/session lock only as an optimization
2. If `sessionId` is supplied, load session and validate active identity fields
3. If `sessionId` is absent, create or reuse session row through DB active identity unique indexes
4. If an identity-selected session's workflow is already closed, mark old row `ENDED` and retry identity create-or-reuse before turn allocation; if an explicit `sessionId` is closed, reject as ended/closed
5. Run preflight rejection
6. Read or create `session_runtime_turn`
7. If existing, reuse `input_allocations`
8. Detect duplicate external messages by `(session_id, external_message_id)`
9. Append only new accepted messages
10. If no new accepted messages, return duplicate response without Temporal update
11. Commit DB work before Temporal start/update calls
12. Ensure workflow execution exists with workflow id = `sessionId`; already-started is success
13. Submit Temporal `submitUserTurn` with update id = `turnId`
14. If Temporal update is rejected before accepted stage, do not mark `WORKFLOW_ACCEPTED`; surface the non-accepted result and preserve recovery state
15. Update status to `WORKFLOW_ACCEPTED` only after accepted stage
