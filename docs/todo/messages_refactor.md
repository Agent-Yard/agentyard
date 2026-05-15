# 多消息一次 Turn 方案

## Summary

采用 **turn -> messages -> blocks** 三层模型：

- `turn`：一次后端处理批次，只触发一次 agent turn。
- `messages[]`：本批次内的多条用户可见消息，保留独立顺序、时间、外部消息 ID、role、sender、metadata。
- `blocks[]`：单条消息内的内容部件，类型保持现状：`TEXT`、`IMAGE`、`RICH_TEXT`、`CARD`。

不把多条用户消息压成一条 message 的多个 block。多 block 只用于“一条消息包含多种内容”，比如文字+图片、文字+附件卡片。

关键约束：一个会话当前只绑定一个 channel；因此 channel outbound 只应投递平台新产生的消息，所有外部进入或导入的消息都不能 outbound。

## New Data Structures

### Session runtime public API

新增 turn 级输入接口，替代当前单条 `SendSessionMessageRequest` 语义：

```http
POST /api/session-runtime/sessions/{sessionId}/turns
Idempotency-Key: {turnDedupKey}
```

```ts
export interface SendSessionTurnRequest {
  customerId: string;
  turnDedupKey: string;
  messages: SessionTurnMessageInput[];
  metadata: Record<string, unknown>;
}

export interface SessionTurnMessageInput {
  clientMessageId?: string | null;
  externalMessageId?: string | null;
  occurredAt?: string | null;
  role: SessionMessageRole;
  sender: SessionMessageSender;
  blocks: SessionMessageBlock[];
  metadata: Record<string, unknown>;
}

export interface SendSessionTurnResponse {
  sessionId: string;
  turnId: string;
  status: SessionMessageDeliveryStatus;
  acceptedMessageIds: string[];
  duplicateExternalMessageIds: string[];
  reason: string | null;
}
```

约束：

- `messages[]` 是本次 turn 的完整输入消息集合，不再拆 `historyMessages` 或 `turnMessageKind`。
- 系统接手时带入的历史对话、用户滞后发送的多条消息、附件消息，都放在同一个 `messages[]` 中，按数组顺序进入 turn。
- `turnDedupKey` 是批次级幂等键；`Idempotency-Key` 必须等于 `turnDedupKey`。
- `clientMessageId` 只用于前端临时关联，不作为数据库主键。
- 对外部输入接口，服务端将所有 request messages 的 `producerType` 固定为 `EXTERNAL`，客户端不能声明 `PLATFORM`。

### Session block types

block 类型保持现状，不新增 `FILE`：

```ts
export type SessionMessageBlock =
  | TextMessageBlock
  | ImageMessageBlock
  | RichTextMessageBlock
  | CardMessageBlock;

export interface TextMessageBlock {
  type: 'TEXT';
  text: string;
}

export interface ImageMessageBlock {
  type: 'IMAGE';
  url: string;
  mimeType: string | null;
  width: number | null;
  height: number | null;
  alt: string | null;
}

export interface RichTextMessageBlock {
  type: 'RICH_TEXT';
  format: 'MARKDOWN';
  content: string;
}

export interface CardMessageBlock {
  type: 'CARD';
  cardType: string;
  version: string;
  data: Record<string, unknown>;
  actions: CardLinkAction[];
}
```

非图片文件附件使用 `CARD`：

```json
{
  "type": "CARD",
  "cardType": "FILE_ATTACHMENT",
  "version": "1",
  "data": {
    "externalAttachmentId": "att-1",
    "externalFileId": "file-1",
    "fileName": "contract.pdf",
    "mimeType": "application/pdf",
    "url": "https://example.com/contract.pdf",
    "sizeBytes": 10240
  },
  "actions": [
    {
      "actionType": "LINK",
      "label": "Open",
      "url": "https://example.com/contract.pdf"
    }
  ]
}
```

### Workflow contracts

新增 workflow update 入参，用一次 update 表示一次 turn：

```java
public record UserTurn(
    String turnId,
    String customerId,
    String turnDedupKey,
    List<SessionTurnMessage> messages,
    Map<String, Object> metadata
) {
}

public record SessionTurnMessage(
    String messageId,
    String clientMessageId,
    String externalMessageId,
    Instant occurredAt,
    SessionMessageRole role,
    SessionMessageSender sender,
    SessionMessageInput message
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

约束：

- workflow 按 `messages[]` 顺序 append 所有消息。
- `executeTurn` 对整个 `messages[]` 只执行一次。
- `SessionTrigger` 只描述 turn 级触发原因，不再携带单条或多条 trigger message id。

### Agent runtime contracts

`agent-runtime` 不再接收完整 session 历史窗口，也不再接收 `triggerMessageId` / `triggerMessageIds`。它只接收本次需要追加到 agent 上下文里的消息增量：

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
    boolean transcriptBootstrap,
    List<SessionEvent> events
) {
}
```

约束：

- `messages` 是 append-only delta：本次 turn 要追加进 agent transcript 的消息集合。
- 正常实时 turn 只传本次 request 产生的新消息。
- 当 agent transcript 缺失、过期、owner context 切换或需要重建时，worker 可以把一批会话消息作为 bootstrap delta 传入，并设置 `transcriptBootstrap = true`。
- `agent-runtime` 以 transcript store 中已提交的 provider transcript 作为上下文基础，再把 `messages` 渲染成 provider messages 追加到末尾。
- 如果 `transcriptBootstrap = true`，`agent-runtime` 必须先清理当前 owner context 下已提交 transcript，再按 `messages` 重建上下文，避免重复追加。
- `agent-runtime` 不再区分“历史消息”和“本轮输入消息”；对它来说，所有 request messages 都是本次追加的 transcript 增量。

Agent turn stream payload 去掉“单个触发 message”的语义。turn 级 frame 只引用平台将要生成的 reply message：

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

约束：

- `TURN_STARTED` / `TURN_COMPLETED` / `ERROR` 不再使用含糊的 `messageId` 字段；统一使用 `replyMessageId`。
- `REPLY_BLOCK_DELTA`、`REPLY_BLOCK_COMPLETED`、`FINAL_OUTCOME` 仍然绑定 assistant reply message；字段也应命名为 `replyMessageId`，不是 trigger message id。
- stream 消费方以 `turnId` 识别一次 turn，以 `replyMessageId` 识别本轮平台输出消息。

### Persistence projection

新增消息生产方枚举：

```java
public enum SessionMessageProducerType {
    EXTERNAL,
    PLATFORM
}
```

含义：

- `EXTERNAL`：外部进入或导入的 transcript 消息，包括 channel inbound、聊天终端用户输入、系统接手时导入的历史 user/assistant/operator/system 消息。
- `PLATFORM`：平台内部新产生的消息，包括 agent 回复、平台内人工客服回复、平台系统消息。

新增 turn 表：

```sql
create table session_runtime_turn (
    turn_id varchar(64) primary key,
    session_id varchar(64) not null,
    dedup_key varchar(128) not null,
    trigger_type varchar(64) not null,
    status varchar(32) not null,
    message_ids jsonb not null,
    metadata jsonb not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone
);

create unique index uk_session_runtime_turn_dedup
    on session_runtime_turn (session_id, dedup_key);
```

扩展 message 表：

```sql
alter table session_runtime_message
    add column turn_id varchar(64),
    add column turn_index integer,
    add column producer_type varchar(32) not null,
    add column external_message_id varchar(255),
    add column client_message_id varchar(255),
    add column occurred_at timestamp with time zone;

create index idx_session_runtime_message_turn
    on session_runtime_message (session_id, turn_id, turn_index);

create unique index uk_session_runtime_message_external
    on session_runtime_message (session_id, external_message_id)
    where external_message_id is not null;
```

`SessionMessage` 投影契约同步暴露新增字段，JVM contracts、TS contracts、Python agent-runtime models、web DTO 都必须保持一致：

```java
public record SessionMessage(
    String messageId,
    String sessionId,
    long sequence,
    String turnId,
    Integer turnIndex,
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

约束：

- `turn_index` 是 message 在 turn 内的顺序。
- `occurred_at` 表示外部消息真实发生时间；`created_at` 仍表示平台落库时间。
- 外部 turn request 写入的消息统一 `producer_type = EXTERNAL`。
- workflow 生成的 agent/system 回复、平台内人工客服回复统一 `producer_type = PLATFORM`。
- channel outbound final replay 只选择 `producer_type = PLATFORM` 且 `role in (ASSISTANT, HUMAN_OPERATOR, SYSTEM)` 的消息。
- `session_runtime_turn.message_ids` 记录本次 turn append 的全部消息，用于审计、UI 分组和 agent transcript bootstrap。

### Channel inbound contracts

新增消息批次入口，替代“一个 normalized event 对应一条 session message”的模型：

```ts
export type NormalizedChannelMessageRole =
  | 'USER'
  | 'ASSISTANT'
  | 'HUMAN_OPERATOR'
  | 'SYSTEM';

export type NormalizedChannelSenderType =
  | 'CUSTOMER'
  | 'AGENT'
  | 'HUMAN_OPERATOR'
  | 'SYSTEM';

export interface NormalizedChannelMessageSender {
  senderType: NormalizedChannelSenderType;
  senderId?: string | null;
  senderName?: string | null;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelInboundTurn {
  providerType: string;
  channelProfileId: string;
  dedupKey: string;
  externalConversationId: string;
  externalUserId?: string | null;
  conversation: NormalizedChannelConversation;
  sender?: NormalizedChannelSender | null;
  messages: NormalizedChannelTurnMessage[];
  normalizedPayload: Record<string, unknown>;
  rawPayload?: Record<string, unknown> | null;
  traceContext: NormalizedChannelTraceContext;
  metadata?: Record<string, unknown> | null;
}

export interface NormalizedChannelTurnMessage {
  externalEventId?: string | null;
  externalMessageId: string;
  occurredAt?: string | null;
  role: NormalizedChannelMessageRole;
  sender?: NormalizedChannelMessageSender | null;
  type?: string | null;
  text?: string | null;
  attachments: NormalizedChannelAttachment[];
  metadata: Record<string, unknown>;
}
```

gateway 到 API 的内部请求：

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

约束：

- `NormalizedChannelInboundTurn.dedupKey` 是批次级幂等键。
- `messages[].externalMessageId` 必填，用于 channel 消息级去重。
- 实时 channel 用户消息设置 `role = USER`；系统接手导入 transcript 时按原始角色设置 `role`。
- message-level `sender` 优先；为空时使用 turn-level `sender`。
- channel boundary 使用 `NormalizedChannelMessageRole` / `NormalizedChannelMessageSender`，gateway 内部再映射为 `SessionMessageRole` / `SessionMessageSender`，避免 extension protocol 直接依赖 session runtime 类型。
- 所有 channel inbound turn messages 写入 session 时均为 `producer_type = EXTERNAL`。
- 图片附件映射为 `IMAGE` block；非图片附件映射为 `CARD(FILE_ATTACHMENT)`。
- pull-style provider job 响应改为 `inboundTurns[]`；非消息类事件继续保留 event 语义。

## Runtime Behavior

- 一次 request 生成一个 `turnId`，按顺序追加 `messages[]`。
- workflow `submitUserTurn` 只执行一次 `executeTurn`，并把本次 append 的 `messages[]` 作为 agent-runtime delta 发送。
- worker 不再每次把会话历史窗口传给 agent-runtime；agent-runtime 通过 transcript store 维护 owner context 下的 provider transcript。
- 幂等以 `turnDedupKey` 为主；channel 场景同时按 `sessionId + externalMessageId` 做消息级去重。
- 如果批次里全部是重复消息，不触发 turn；如果有重复和新消息混合，只追加新消息并对新消息触发一次 turn。
- 如果 session 正在 agent turn active，整个 turn 请求返回 busy，不做部分写入。
- 系统接手场景中，历史 transcript 与当前待处理消息统一放入同一个 `messages[]`；它们都会作为本次 transcript delta 追加到 agent-runtime 上下文。
- 当 agent-runtime transcript 不存在或需要重建时，worker 从权威 session message 表构造 bootstrap `messages[]`，而不是让 agent-runtime 自行读取 session runtime 数据。

## Test Plan

- 契约测试：OpenAPI、JVM contracts、TS contracts 覆盖 `SendSessionTurnRequest`、`UserTurn`、`NormalizedChannelInboundTurn`。
- API 服务测试：多条 `messages[]` 一次请求只调用一次 workflow update；空 messages 被拒绝。
- Worker 测试：`submitUserTurn` 追加多条消息，sequence 正确，`executeTurn` 一次，agent-runtime request 只携带本次 delta messages。
- Agent-runtime 测试：无 committed transcript 时用 `messages` 初始化 prompt；有 committed transcript 时追加 `messages`；`transcriptBootstrap = true` 时重建 transcript 且不重复历史；stream payload 使用 `replyMessageId` 且不暴露 trigger message id。
- Channel gateway 测试：一个 inbound turn 多条外部消息只 dispatch 一次；重复 externalMessageId 不重复落 session message。
- UI 测试：同一 turn 下多条 message 仍按独立气泡展示，每条 message 内 blocks 正常渲染。
- Projection 测试：API/web/agent-runtime 读到的 `SessionMessage` 均包含 `turnId`、`turnIndex`、`producerType`、`externalMessageId`、`clientMessageId`、`occurredAt`。
- 附件测试：图片映射为 `IMAGE`；非图片附件映射为 `CARD(FILE_ATTACHMENT)`，不引入新 block 类型。
- Outbound 测试：`producer_type = EXTERNAL` 的 assistant/operator/system 历史消息不会生成 channel `FINAL_DELIVERY`；`producer_type = PLATFORM` 的 agent/operator/system 新消息会生成。

## Assumptions

- 不考虑旧单消息接口兼容，直接向 turn 语义重构。
- block 类型严格保持现状：`TEXT`、`IMAGE`、`RICH_TEXT`、`CARD`。
- 一个 session 当前只绑定一个 channel。
- “一次处理”以 turn 为单位，“多条消息展示/审计/去重”以 message 为单位。
