# Channel Outbound Frame Stream 改造方案

## 1. 目标结论

当前 `API -> channel-gateway -> extension channel provider` 的 outbound 链路要从“每个 frame 一次 HTTP POST”改成“API 按 channel profile 派生 frame，channel-gateway 按 profile consumer 稳定维护 outbound frame stream”。

目标形态：

```text
API
  -- internal SSE: profile-level outbound frame stream -->
channel-gateway
  -- extension-facing SSE: profile-consumer outbound frame stream -->
extension channel provider
```

核心决策：

1. inbound 事件提交不复用为 outbound SSE。inbound 仍然快速 ACK。
2. API internal SSE 以 `channelProfileId` 为派生维度；channel-gateway relay、checkpoint 和 downstream SSE 以 `channelProfileId + providerType + consumerKind + consumerId` 的 profile consumer 为消费维度。
3. transient frame 只服务实时体验，短 TTL，断线后可以丢弃。
4. `FINAL_DELIVERY` 不进入 API outbox；frame payload 从 core DB 的 durable session message 派生。
5. channel-gateway 不持久化 provider delivery result，但持久化 extension/native provider ACK 后的 final checkpoint。
6. 恢复以 `lastAckedFinalSequence` 为 durable ordered checkpoint，从下一个未 ACK final message 开始续接，不补中间 draft。
7. channel-gateway 不承担业务消息源，只做 profile stream relay、连接治理、capability filter 和 final ACK checkpoint。
8. 去重责任由稳定 `frameId / idempotencyKey` 承担，extension/provider 必须按 `frameId` 幂等。
9. 不做“旧 POST activity 先保留、后续逐步切换”的兼容路线。实现时直接把 channel outbound 实时链路重构到 frame stream。

## 2. 数据与恢复分层

outbound frame 分两类：

```text
Transient frames:
  TYPING_START
  TYPING_STOP
  DRAFT_UPDATE
  DRAFT_COMPLETE
  DRAFT_DISCARD

Final frames:
  FINAL_DELIVERY
```

transient frame：

1. 来源是 runtime transient stream。
2. 只进入 API 的短期 replay buffer。
3. TTL 建议 1-5 分钟。
4. 断线超过 TTL 后直接丢弃。
5. 不参与 durable recovery。

final frame：

1. 来源是 core DB 中已经持久化的 session final message。
2. frame payload 可由 durable message + API binding snapshot 重新派生。
3. 不依赖 Redis replay。
4. 不依赖 API outbox。
5. 不依赖 channel-gateway frame/result 持久化。
6. channel-gateway 只持久化 consumer 级 ACK checkpoint：`lastAckedFinalSequence / lastAckedFinalFrameId`。
7. ACK checkpoint 不是 delivery result ledger，不保存 raw provider response，不作为业务消息源。

恢复语义：

```text
live stream:
draft...draft -> FINAL_DELIVERY(db) -> draft...draft -> FINAL_DELIVERY(db)

recovery:
从 lastAckedFinalSequence 之后的下一个 FINAL_DELIVERY 开始补
中间所有 draft 直接丢弃
```

断线重连后：

1. 如果 transient replay buffer 仍命中，可以补近期 transient frame。
2. 如果 transient replay buffer miss，不补 draft，不从中间继续 draft。
3. 如果某个 turn 正在生成中且 draft 前缀已错过，等这个 turn 的 `FINAL_DELIVERY`。
4. final replay 只从 durable `lastAckedFinalSequence` 后的下一条 final message 开始派生。
5. `Last-Event-ID` 只服务 transient SSE replay，不能作为 final checkpoint。

## 3. 为什么不能复用 inbound 请求

inbound 是一次性事件提交，outbound 是长期订阅通道。两者生命周期不同：

1. inbound 必须快速返回，避免外部平台或 extension 重试。
2. outbound frame 可能来自 playbook、人工回复、session replay、主动通知，不一定由刚刚提交的 inbound 触发。
3. SSE 断线恢复依赖 profile 级 cursor，而不是某一次 inbound request。
4. 同一个 profile 下可能有多个 session/conversation 同时产生 outbound frame。

协议必须拆开：

```text
extension -> channel-gateway
POST /extension/channel/inbound-events

extension -> channel-gateway
GET /extension/channel/outbound-frames/stream?channelProfileId=...
```

## 4. Frame 协议

统一协议名：

```text
agentyard.channel-outbound-frame.v1
```

frame body：

```json
{
  "protocol": "agentyard.channel-outbound-frame.v1",
  "frameId": "channel-profile-1:exec-1:7:DRAFT_UPDATE",
  "channelProfileId": "channel-profile-1",
  "providerType": "enterprise.acme.im",
  "assistantId": "assistant-1",
  "externalConversationId": "chat-1",
  "sessionId": "session-1",
  "turnId": "turn-1",
  "turnExecutionId": "exec-1",
  "sourceSeq": 7,
  "kind": "DRAFT_UPDATE",
  "occurredAt": "2026-05-04T10:00:00Z",
  "idempotencyKey": "cof-0123456789abcdef0123456789abcdef",
  "payload": {
    "messageId": "session-message-reply-1",
    "blockId": "reply-block-1",
    "blockType": "TEXT",
    "delta": "hello"
  }
}
```

field 规则：

1. frame 不包含 raw token、account secret、provider native secret，也不下发 credential reference。
2. remote extension provider 自己管理外部平台 credential；core 最多通过独立 API 做受控中转，不把凭证上下文塞进 outbound frame。
3. gateway-native provider 在 channel-gateway 内部按 profile/account 解析凭证。
4. `turnId / turnExecutionId / sourceSeq` 只对 transient frame 必填；`FINAL_DELIVERY` 可以不带这些字段。
5. `finalSequence` 只对 `FINAL_DELIVERY` 必填；transient frame 不带 `finalSequence`。
6. `frameId` 是 ACK/checkpoint 与 extension 本地幂等身份；`idempotencyKey` 是短 provider 投递幂等键，稳定派生自 `frameId`，当前格式为 `cof-` + SHA-256(frameId) 前 32 位 hex，长度不超过 50；`finalSequence` 是 durable final message 的全局排序字段，三者不能混用。

frame kinds：

```text
TYPING_START
TYPING_STOP
DRAFT_UPDATE
DRAFT_COMPLETE
DRAFT_DISCARD
FINAL_DELIVERY
```

`frameId` 规则：

1. transient frame：`{channelProfileId}:{turnExecutionId}:{sourceSeq}:{kind}`。
2. 本轮和目标架构不做 `DRAFT_UPDATE` 短窗口合并；draft/transient 可以逐 frame SSE。
3. final delivery：`{channelProfileId}:{sessionId}:{sessionMessageId}:FINAL_DELIVERY`。
4. 同一个业务动作重放时必须生成相同 `frameId`。
5. extension 必须把 `frameId` 当作本地幂等身份。重复收到同一个 `frameId` 时不得重复发送外部消息；provider API 如支持 idempotency key，优先使用 frame 中的短 `idempotencyKey`。

`finalSequence` 与 checkpoint 规则：

1. API 新增 DB 全局单调字段 `session_runtime_message.final_sequence`，由 PostgreSQL sequence 在 final message 落库时生成。
2. `finalSequence` 是 profile consumer 跨 session final replay 的主排序字段。
3. `message.sequence` 仍表示 session 内顺序，不能作为 profile consumer 跨 session replay 的主排序。
4. checkpoint 的恢复锚点是 `lastAckedFinalSequence`；`lastAckedSessionId / lastAckedSessionMessageId / lastAckedFinalFrameId` 用于完整性校验、观测和幂等诊断。
5. API durable replay 查询使用 `finalSequence > lastAckedFinalSequence` 做大于比较；不使用 `frameId`、`message.sequence` 或 `createdAt` 推断跨 session 顺序。
6. 同一个 final message 重放时必须生成相同 `finalSequence / frameId`。
7. 不引入额外的 opaque final replay token。API、channel-gateway 和 extension ACK 直接传递结构化 checkpoint 字段，避免 opaque token 与结构化字段重复。

payload 约定：

1. `TYPING_START / TYPING_STOP`
   - 必须带 `messageId`。
   - 可以不带 block 字段。
2. `DRAFT_UPDATE`
   - 必须带 `messageId / blockId / blockType`。
   - `TEXT` block 必须带 `delta`。
   - draft 是 best-effort transient 体验，可以逐 source frame 下发，不参与 final/ACK/checkpoint 正确性。
3. `DRAFT_COMPLETE`
   - 必须带 `messageId / blockId / block`。
   - `block` 是 canonical session message block。
   - 只表示 transient draft 结束，不代表正式外部消息已经发送。
4. `DRAFT_DISCARD`
   - 必须带 `messageId`。
   - 可以带 `reason`。
5. `FINAL_DELIVERY`
   - 必须带 `finalSequence / sessionMessageId / messageSequence / messageBlocks`。
   - 可以带 `resolvedTemplate`。
   - `messageBlocks` 是 durable final session message 的完整 canonical blocks，保持 `TEXT / IMAGE / RICH_TEXT / CARD` canonical 结构，不把 provider native payload 泄露回 session 模型。

## 5. Draft 与 Final 的外部语义

为避免双发，必须固定以下语义：

1. `FINAL_DELIVERY` 是唯一正式外部消息发送动作。
2. `DRAFT_UPDATE / DRAFT_COMPLETE / DRAFT_DISCARD` 只表示临时体验。
3. 对支持 draft 的 provider，`DRAFT_COMPLETE` 只能完成或关闭临时草稿态，不能再发送一条正式消息。
4. 对不支持 draft 的 provider，channel-gateway 不下发 `DRAFT_*`。
5. 无论 provider 是否支持 draft，最终外部可见消息都由 `FINAL_DELIVERY` 触发。
6. frame 拆分只服务底层 agent-runtime 的流式体验；如果 durable final 结果已经是一条 session message，后续 API、channel-gateway 和 provider adapter 不再把它拆成多个 `FINAL_DELIVERY` frame。
7. provider 如果必须把一个 session message 渲染成多条 native message，那是 provider adapter 内部实现细节；只有所有 native send 成功或幂等确认后，才能 ACK 同一个 `FINAL_DELIVERY`。

## 6. API Binding Snapshot

API 需要把 session outbound 发回唯一绑定的 channel conversation。channel-gateway 仍是 `ChannelConversationBinding` 的权威存储；API 在 PostgreSQL 维护只读 snapshot；Redis 只用于 refresh request 广播、single-flight lease、cache invalidation，不作为 snapshot 权威存储。

`ChannelConversationBinding` 的业务语义：

1. 一个 binding 表示一个外部 logical conversation 和一个 AgentYard session 的一对一绑定。
2. 同一个 `sessionId` 在 ACTIVE binding 中必须唯一，不能跨 profile 或同 profile 多 conversation 分发。
3. 同一个 `channelProfileId + externalConversationId` 在 ACTIVE binding 中必须唯一。
4. 多终端、多设备、多外部线程别名由 provider/adapter normalize 成同一个 logical `externalConversationId`，不创建多个 ACTIVE binding。
5. 系统不支持“先存在独立网页 session，之后再绑定到外部 channel conversation”的路径。

binding 生命周期不变量：

1. 外部 conversation 进入系统时，由 channel-gateway 创建或找到对应 binding，再 dispatch 到 API 创建/获取 session。
2. `attachSession` 必须在该 session 产生任何可外发 `FINAL_DELIVERY` 之前持久化完成。
3. API 在发布 outbound 前必须能通过 snapshot 或 by-session refresh 找到唯一 ACTIVE binding；找不到时 fail closed，不外发。
4. binding 是 session 一对一；final checkpoint 是 profile consumer 级，不是单个 session/binding 级。
5. checkpoint 为空表示该 profile consumer 从未 ACK 过 final，因此可以从该 profile 下所有 ACTIVE binding 可见的最早 durable final 开始 replay。
6. 这个模型不需要额外的 `outbound_start_final_sequence`；正确性来自“一对一 binding 先于 outbound final”这个不变量。

```text
channel_session_binding_snapshot
  binding_id
  session_id
  channel_profile_id
  provider_type
  external_conversation_id
  external_user_id
  assistant_id
  customer_id
  binding_status
  profile_status
  profile_revision
  binding_updated_at
  profile_updated_at
  updated_at
```

索引与约束：

1. `binding_id` 是 snapshot 主键。
2. `session_id` 在 ACTIVE snapshot 中唯一；API snapshot 需要用 partial unique index 或等价校验镜像 channel-gateway 的权威约束。
3. `channel_profile_id + external_conversation_id` 在 ACTIVE snapshot 中唯一。
4. 增加索引 `(session_id)`、`(channel_profile_id, session_id)`、`(channel_profile_id, external_conversation_id)`。
5. outbound 发布按 `sessionId` 查唯一 ACTIVE snapshot，只发送到该 binding 对应的 `channelProfileId + externalConversationId`。
6. 如果 API 查询到同一 `sessionId` 对应多个 ACTIVE snapshot，必须视为数据完整性错误：fail closed、不发送 outbound、记录 error metric/log。

同步规则：

1. API 启动后全量分页拉取 channel-gateway 的 ACTIVE bindings，并把本地不在 gateway ACTIVE 集合中的 snapshot 标记 inactive。
2. profile create/update/disable/delete 由 API 中转时，API 对对应 `channelProfileId` 触发 snapshot refresh。
3. channel-gateway 每次 binding/profile 变更后，都向 API 发送 refresh hint；API 只把 hint 当触发信号，必须回源 channel-gateway 拉取权威 binding/profile，不从 hint body 手写 upsert。
4. 新增 binding 的关键时序：gateway inbound ingest 可先创建无 `sessionId` 的 pending/unattached binding；gateway dispatch 调 API 得到 `sessionId` 后，必须先持久化 `attachSession` 并使其满足 ACTIVE 唯一约束，再通知 API refresh；在 `attachSession` 完成前，该 session 不允许产生可外发 `FINAL_DELIVERY`。
5. API 增加定时同步兜底，按 `updatedAfter` 增量拉取；增量结果必须包含 status change tombstone，不能只返回 ACTIVE bindings。
6. profile disabled/deleted 时，API 删除或标记该 profile 下 snapshot inactive。
7. binding status 非 ACTIVE 或 `sessionId` 为空时，不参与 outbound frame lookup。
8. outbound frame 发布前，如果本地 snapshot 缺失，API 允许按 `sessionId` 触发一次 by-session refresh 后再判定；仍缺失则跳过 channel outbound，并记录 structured log。

多 API 实例 refresh 规则：

1. refresh hint 可能打到任意 API 实例；收到 hint 的实例不得只刷新本机状态。
2. 所有 refresh 入口必须先规范化为 shared refresh request，key 优先级为 `bindingId` -> `sessionId` -> `channelProfileId`。
3. shared refresh request 通过 Redis 广播给所有 API 实例；发起实例也走同一套消费路径，不保留直接本地刷新捷径。
4. 消费 shared refresh request 时，必须先获取 keyed single-flight lease：`channel-binding-snapshot-refresh:{key}`。
5. 只有拿到 lease 的实例回源 channel-gateway，写 PostgreSQL `channel_session_binding_snapshot`；没拿到 lease 的实例直接跳过。
6. 同一 key 在 1-3 秒窗口内 debounce/merge，避免 `attachSession`、profile revision update、status update 连续触发重复 refresh。
7. 写入 PostgreSQL 后发布 snapshot invalidation/version event；其他 API 实例只刷新本地 cache 或下次查询 PostgreSQL。
8. refresh request 广播不承担强一致投递；hint 丢失由启动全量、定时 reconcile、outbound 前 by-session refresh 兜底。

gateway -> API refresh hint：

```http
POST /api/internal/channel-outbound/binding-snapshot/refresh
Authorization: Bearer {internal-token}
Content-Type: application/json
```

```json
{
  "channelProfileId": "channel-profile-1",
  "bindingId": "channel-binding-1",
  "sessionId": "session-1",
  "reason": "BINDING_SESSION_ATTACHED",
  "bindingUpdatedAt": "2026-05-04T10:00:00Z"
}
```

channel-gateway 需要提供的 snapshot 拉取 API：

```http
GET /internal/channel-admin/bindings?updatedAfter=...&cursor=...&limit=...
GET /internal/channel-admin/bindings/by-session/{sessionId}
GET /internal/channel-admin/profiles/{channelProfileId}/bindings
```

这些 API 返回 redacted binding/profile surface，不返回 raw credential。`by-session` 对 ACTIVE binding 最多返回一条；如果 channel-gateway 发现多条 ACTIVE binding，必须返回数据完整性错误而不是让 API 自行选择。

增量 tombstone 规则：

1. `updatedAfter` API 必须返回 `ACTIVE / DISABLED / DELETED / DETACHED` 等状态变更。
2. tombstone 至少包含 `bindingId / sessionId / channelProfileId / providerType / bindingStatus / profileStatus / updatedAt`。
3. API 收到 tombstone 后必须把对应 snapshot 标记 inactive 或删除，不能继续参与 outbound lookup。
4. 全量 reconcile 不只是追加 ACTIVE snapshot；还必须把本地存在但 gateway ACTIVE 集合不存在的 snapshot 标记 inactive。

## 7. API -> channel-gateway internal SSE

API 新增 internal endpoint：

```http
GET /api/internal/channel-outbound/frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {internal-token}
Last-Event-ID: {streamCursor}
X-AgentYard-Last-Acked-Final-Sequence: {lastAckedFinalSequence}
X-AgentYard-Last-Acked-Session-Id: {lastAckedSessionId}
X-AgentYard-Last-Acked-Session-Message-Id: {lastAckedSessionMessageId}
X-AgentYard-Max-Final-Replay-Frames: {maxFinalReplayFrames}
```

SSE event：

```text
id: 42
event: channel-outbound-frame
data: {"protocol":"agentyard.channel-outbound-frame.v1", "...": "..."}
```

replay window exhausted event：

```text
event: final-replay-window-exhausted
data: {"emitted":100,"lastEmittedFinalSequence":100000142}
```

stream 与 checkpoint 规则：

1. SSE `id` 是短期 `streamCursor`，用于 transient replay。
2. `streamCursor` 可以过期。
3. `lastAckedFinalSequence` 是 final checkpoint，用于 durable final replay。
4. 如果 `Last-Event-ID` 命中 transient replay buffer，API 可以补近期 transient frames。
5. 如果 `Last-Event-ID` miss，API 直接跳过 transient frames。
6. API 总是从 `lastAckedFinalSequence` 之后的 durable final message 开始派生 `FINAL_DELIVERY`。
7. 如果 `lastAckedFinalSequence` 为空，API 从该 profile 下所有 ACTIVE binding 可见的最早 durable final message 开始派生。
8. `FINAL_DELIVERY` event data 必须带 `finalSequence`。
9. `X-AgentYard-Max-Final-Replay-Frames` 是单次连接的 durable final replay 上限，API 最多补该数量的 `FINAL_DELIVERY`；缺省值建议 100，API 必须有服务端最大值保护。
10. 如果达到 replay window 但 durable final backlog 仍未追平，API 发送 `final-replay-window-exhausted` 后正常结束连接；gateway 等 pending final 降到低水位后再用最新 checkpoint 重新连接。
11. 如果 durable final replay 已追平，API 保持 SSE 连接，继续发送 live transient/final frames。
12. 如果 checkpoint header 非法，API 返回 400/422，不静默降级为全量 replay。
13. API internal SSE 在 live idle 期间发送 heartbeat comment，默认 20 秒；heartbeat 不带 `id`、不推进 `streamCursor`，也不参与 final checkpoint，只用于避免代理/网关 idle timeout 断开长期空闲连接。

API 侧职责：

1. `SessionRuntimeStreamService.acceptStreamFrame` 不再直接触发 `ChannelGatewayClient.sendOutboundActivity`。
2. `SessionChannelActivityRelay` 改为基于 binding snapshot 写入 `ChannelOutboundFramePublisher`。
3. `SessionChannelOutboundRelay` 不再直接调用 `ChannelGatewayClient.deliverOutbound`，而是让 durable session message 可被 `FINAL_DELIVERY` 派生器读取。
4. API 对每个 `channelProfileId` 维护短期 replay buffer，用于 transient frames。
5. API 提供 final frame 派生器，从 core DB session messages + binding snapshot 生成有序 `FINAL_DELIVERY`。
6. API 多实例下，transient frame publish 通过 Redis Pub/Sub 广播给本实例 SSE subscriber。
7. API 不保存 extension ACK；ACK checkpoint 的权威存储在 channel-gateway。

API 不做：

1. 不维护 channel outbound final outbox。
2. 不持久化 provider delivery result。
3. 不保证 transient frame 完整恢复。

## 8. channel-gateway profile consumer relay

channel-gateway 后台维护 profile consumer 级订阅。consumer 指 remote extension consumer 或 gateway-native provider consumer：

consumer identity：

1. `consumerKind` 取值：`REMOTE_EXTENSION` / `GATEWAY_NATIVE`。
2. `consumerId` 必须非空。
3. remote extension 的 `consumerId = registrationId`。
4. gateway-native provider 的 `consumerId = native:{providerType}`，或等价稳定 sentinel。
5. profile consumer 的唯一身份是 `channelProfileId + providerType + consumerKind + consumerId`。
6. 同一个 ACTIVE `channelProfileId` 的 outbound consumer 类型由 provider registration/profile 唯一决定，`REMOTE_EXTENSION` 与 `GATEWAY_NATIVE` 互斥；gateway 不会同时为同一 profile 启动 remote extension consumer 和 gateway-native consumer。

```text
ACTIVE channel profile + consumer
  -> gateway instance 获取 profile consumer stream ownership
  -> 连接 API internal SSE
  -> capability filter
  -> 发布给 extension-facing SSE subscriber
```

多实例规则：

1. 同一个 `channelProfileId + providerType + consumerKind + consumerId` 同一时间只能有一个 channel-gateway instance 订阅 API internal SSE。
2. 使用 Redis lock 或 PostgreSQL advisory lock 实现 `channel-outbound-api-stream-owner:{channelProfileId}:{providerType}:{consumerKind}:{consumerId}`。
3. owner 失联后 lock 过期，其他 gateway instance 接管。
4. 接管时使用本地连接状态中的 `Last-Event-ID` 和 checkpoint 表中的 `lastAckedFinalSequence` 重新连接 API。

channel-gateway 不做：

1. 不持久化 final frame。
2. 不持久化 final result。
3. 不维护 provider delivery result ledger。
4. 不把 transient frame 作为 durable fact。

channel-gateway 要做：

1. 维护 final ACK checkpoint 表，替代旧 `channel_outbound_delivery`。
2. 对 remote extension，只有收到 extension ACK 后才推进 checkpoint。
3. 对 gateway-native provider，只有外部平台发送成功或 provider 幂等确认后才推进 checkpoint。
4. ACK checkpoint 只用于恢复，不作为 frame payload、provider result 或业务消息源。
5. 对 remote extension，在下发 `FINAL_DELIVERY` 前写入 Redis forwarded pending state，用于校验 ACK 只能按 gateway 下发顺序推进。
6. 对 gateway-native provider，同一个 profile consumer 下的 `FINAL_DELIVERY` 必须按 API stream 顺序串行发送并推进 checkpoint，不能并发越序推进。

forwarded pending 规则：

```text
coff:{channelProfileId}:{providerType}:{consumerKind}:{consumerId}:{frameIdHash}
  frameId
  finalSequence
  sessionId
  sessionMessageId
  forwardedAt

cofp:{channelProfileId}:{providerType}:{consumerKind}:{consumerId}
  ordered list of frameIdHash
```

1. gateway 在向 extension SSE 写出 `FINAL_DELIVERY` 前写 Redis marker，并把 `frameIdHash` 追加到该 profile consumer 的 pending ordered list。
2. `frameIdHash` 使用 `SHA-256(frameId)` 的前 16 或 32 位 hex；完整 `frameId` 放在 value 中校验。
3. 如果同一个 `frameIdHash` 已在 pending list 中，不重复追加，只刷新 marker TTL。
4. marker 与 pending list TTL 默认 5 分钟，可按 provider 发送耗时配置，最大建议 15 分钟；它们不是 durable backlog。
5. ACK 只能接受 pending list 的队首 final；队首之前的 final 未 ACK 时，后续 final 即使命中 marker 也不能推进 checkpoint。
6. marker 或 pending list 过期后收到 ACK，不推进 checkpoint；gateway 返回 409/422，由 extension 主动断开并重连 stream，触发 gateway 用持久化 checkpoint 向 API 重新订阅 replay。
7. marker 提前写入但 SSE 写出失败是安全的：没有 ACK 就不会推进 checkpoint，marker 到期后自然消失。
8. marker 只证明 gateway 尝试向该 profile consumer 下发过该 final，不保存 provider response、credential 或 external payload。
9. pending list 长度必须受 profile consumer 级高水位限制；达到高水位时 gateway 停止读取 upstream API SSE，不再追加新的 pending final。
10. `maxFinalReplayFrames` 不能大于当前 pending 剩余容量；gateway 每次连接 API 时按 `maxPendingFinals - currentPendingFinals` 计算本次 replay credit。

新表建议：

```text
channel_outbound_final_checkpoint
  channel_profile_id
  provider_type
  consumer_kind
  consumer_id
  registration_id
  last_acked_final_sequence
  last_acked_final_frame_id
  last_acked_session_id
  last_acked_session_message_id
  last_acked_at
  created_at
  updated_at
```

约束：

1. `consumer_id` 必须非空；remote extension 用 `registrationId`，gateway-native 用 `native:{providerType}`。
2. 唯一键：`(channel_profile_id, provider_type, consumer_kind, consumer_id)`。
3. `registration_id` 只作为 remote extension metadata；不能作为 checkpoint 唯一键的必要字段。
4. `last_acked_final_sequence` 是 core DB durable final message 的全局排序 checkpoint，只能单调前进。
5. 重复 ACK 或旧 sequence ACK 是 no-op。
6. 不保存 raw provider response、raw credential、raw external payload。
7. 旧 `channel_outbound_delivery` 表和相关 admin/API/UI surface 删除，不做兼容保留。

downstream 恢复规则：

1. extension 断线期间，channel-gateway 不把未 ACK final 视为已完成。
2. extension 重连时，channel-gateway 读取持久化 checkpoint，把 `lastAckedFinalSequence` 传给 API。
3. extension header 中自带的 final sequence 只能作为诊断 hint，不能推进 gateway checkpoint。
4. 因此 extension-facing final recovery 不依赖 channel-gateway 本地 backlog。
5. transient frame 仍只按 channel-gateway 当前内存/短期 replay 能力尽量补，miss 后直接跳过。
6. 如果 downstream 不存在或 backpressure 过大，gateway 可以关闭 stream；未 ACK final 会在下次从 checkpoint 重新派生。

channel-gateway 可以维护的运行态状态：

```text
channelProfileId
providerType
consumerKind
consumerId
upstreamStreamCursor
lastAckedFinalSequence
downstreamStreamCursor
connectedAt
lastHeartbeatAt
```

除 final ACK checkpoint 外，这些状态可以是内存 + Redis lease，不是业务持久化账本。

## 9. channel-gateway -> extension SSE

extension 面向 channel-gateway 建立 profile 级长连接：

```http
GET /extension/channel/outbound-frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {extension-token}
X-AgentYard-Extension-Registration-Id: {registrationId}
X-AgentYard-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-AgentYard-Extension-Descriptor-Id: {providerType}
Last-Event-ID: {streamCursor}
X-AgentYard-Last-Acked-Final-Sequence: {diagnosticLastAckedFinalSequence}
```

SSE event：

```text
id: 105
event: channel-outbound-frame
data: {"protocol":"agentyard.channel-outbound-frame.v1", "frameId":"...", "...":"..."}
```

连接规则：

1. extension client 应在 channel profile ACTIVE 后连接并稳定维护。
2. extension client 不根据 inbound、playbook、session 或 turn 判断何时连接。
3. 对 extension stream，`consumerKind=REMOTE_EXTENSION`，`consumerId=registrationId`。
4. 同一个 `channelProfileId + providerType + consumerKind + consumerId` 同一时间只允许一个 active extension stream consumer。
5. 如果第二个 consumer 连接，推荐返回 `409 CONFLICT`；如果选择踢掉旧连接，必须记录 structured log。
6. SSE heartbeat 使用 comment，间隔建议 15-30 秒。

extension checkpoint 规则：

1. extension 必须在成功幂等处理 `FINAL_DELIVERY` 后，调用 ACK endpoint。
2. 如果收到 final 后进程崩溃、外部平台发送失败或本地幂等记录未落盘，不得 ACK；下次由 gateway checkpoint 重新 replay。
3. `Last-Event-ID` 只代表短期 stream cursor，不能替代 `finalSequence` checkpoint。
4. 对 transient frame，extension 不需要维护 durable checkpoint。
5. extension 可以持久化本地 `lastAckedFinalSequence` 用于自检，但 gateway 持久化 checkpoint 才是 API replay 的权威输入。

extension discovery：

1. channel-gateway 提供 bootstrap endpoint，供 extension 查询自己可订阅的 ACTIVE profiles。
2. bootstrap 必须按 `registrationId / providerType` 授权过滤。
3. bootstrap response 至少包含 `channelProfileId / providerType / status / streamUrl / revision / updatedAt`。
4. bootstrap response 应包含 gateway 持久化的 `lastAckedFinalSequence`，便于 extension 自检本地进度。
5. extension 启动后先调用 bootstrap，再为每个 ACTIVE profile 建立 SSE。
6. extension 定时重新 bootstrap，发现新增 profile 时建立连接，发现 disabled/deleted profile 时关闭连接。

bootstrap endpoint：

```http
GET /extension/channel/outbound-frame-subscriptions
Authorization: Bearer {extension-token}
X-AgentYard-Extension-Registration-Id: {registrationId}
X-AgentYard-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-AgentYard-Extension-Descriptor-Id: {providerType}
```

## 10. Extension 幂等与 provider 发送

extension 必须实现 `frameId` 幂等。

`FINAL_DELIVERY frameId` 必须按 final session message 稳定派生：

```text
{channelProfileId}:{sessionId}:{sessionMessageId}:FINAL_DELIVERY
```

extension 收到重复 `FINAL_DELIVERY`：

1. 如果本地已处理过该 `frameId`，直接 no-op。
2. 如果 provider 支持 idempotency key，把 frame 中的短 `idempotencyKey` 透传给 provider。
3. 如果 provider 不支持幂等，extension 自己必须持久化 `frameId -> externalMessageId`。
4. 如果 extension 无法提供幂等能力，该 provider 不能声明 `supportsFinalDelivery=true`。

ACK endpoint：

```http
POST /extension/channel/outbound-frames/ack
Authorization: Bearer {extension-token}
X-AgentYard-Extension-Registration-Id: {registrationId}
X-AgentYard-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-AgentYard-Extension-Descriptor-Id: {providerType}
Content-Type: application/json
```

```json
{
  "protocol": "agentyard.channel-outbound-frame-ack.v1",
  "channelProfileId": "channel-profile-1",
  "providerType": "enterprise.acme.im",
  "frameId": "channel-profile-1:session-1:message-1:FINAL_DELIVERY",
  "finalSequence": 100000042,
  "sessionId": "session-1",
  "sessionMessageId": "message-1"
}
```

ACK 规则：

1. ACK 只适用于 `FINAL_DELIVERY`。
2. extension 必须按 SSE 顺序处理 final frame，并在本地幂等记录落盘后 ACK。
3. gateway 校验 registration/provider/profile ACTIVE 和 capability。
4. 重复 ACK 或旧 sequence ACK 返回成功 no-op。
5. 新 sequence ACK 必须命中当前 profile consumer 的 Redis forwarded marker，且 marker 内 `finalSequence / frameId / sessionMessageId` 与 ACK body 一致。
6. 新 sequence ACK 必须对应 pending ordered list 的队首 `frameIdHash`；非队首 ACK 返回 409/422，不推进 checkpoint。
7. 未命中 forwarded marker 或 pending list 已过期的新 sequence ACK 返回 409/422，不推进 checkpoint；这表示 gateway 当前无法证明该 final 已按顺序向该 consumer 下发过。
8. extension 收到 409/422 后必须主动重连 stream；gateway 不在 ACK 请求内同步 replay。
9. 校验通过后，gateway 单调推进 `channel_outbound_final_checkpoint`，删除对应 forwarded marker，并从 pending ordered list 弹出队首。
10. 新 sequence ACK 不能包含 raw provider response；必要观测信息只能放 sanitized metadata。
11. provider 发送失败、本地幂等记录失败或进程崩溃时不得 ACK。

remote extension 凭证：

1. outbound frame 不携带 credential reference，也不携带 raw credential。
2. remote extension provider 自己管理外部平台 credential。
3. 如果后续需要 core 中转外部平台调用，必须单独定义 API 边界、授权和审计语义；不能把凭证引用加入 outbound frame。

## 11. Capability 与 manifest 改造

channel provider descriptor 的 outbound 能力收敛为明确对象：

```json
{
  "outbound": {
    "mode": "FRAME_STREAM",
    "supportsTyping": true,
    "supportsDraftUpdate": true,
    "supportsFinalDelivery": true,
    "requiresIdempotentFinalDelivery": true
  }
}
```

旧字段处理：

1. 移除 `capabilities.typing / capabilities.draftUpdate`。
2. 移除 `endpoints.sendActivity`。
3. 移除 remote provider 的 `endpoints.sendOutbound` 同步投递语义。
4. gateway-native provider 也按同一 frame interface 实现，不绕过 frame 协议。

descriptor 校验：

1. `outbound.mode` 必须是 `FRAME_STREAM`。
2. `supportsFinalDelivery` 必须为 true；否则该 channel provider 不能绑定 ACTIVE profile。
3. `requiresIdempotentFinalDelivery` 必须为 true。
4. 如果 `supportsDraftUpdate=false`，gateway 不向 extension 下发 `DRAFT_UPDATE / DRAFT_COMPLETE / DRAFT_DISCARD`。
5. 如果 `supportsTyping=false`，gateway 不下发 `TYPING_*`。

## 12. Backpressure 与 transient 策略

不能把每个模型 token 无限制推给 extension。

API 发布侧：

1. 不做 `DRAFT_UPDATE` 短窗口合并；transient frame 可以逐 frame SSE。
2. draft 是 best-effort，不影响 `FINAL_DELIVERY`、ACK 或 checkpoint 正确性。
3. customer-visible 校验仍在 API 边界完成，不能把 INTERNAL/DEVELOPER frame 投到 channel outbound frame。

channel-gateway 下发侧：

1. 如果 extension SSE consumer 落后太多，transient frame 可以过期丢弃。
2. `FINAL_DELIVERY` 不允许被标记为已完成；如果 backpressure 导致连接关闭，未 ACK final 从 checkpoint 重新派生。
3. 同一个 final delivery 的重发必须使用同一个 `frameId` 和 `idempotencyKey`。

final replay window：

1. final replay 是 gateway 控制的 credit/window 模型，不允许 API 向 gateway 无限推送 durable backlog。
2. remote extension consumer 没有 active downstream SSE 连接时，gateway 不订阅 API internal SSE；extension 重连后再按 gateway checkpoint 补 final。
3. remote extension consumer 默认 `maxPendingFinals=100`、`resumePendingFinals=20`；具体值可按 provider 配置，但必须小于 pending marker TTL 内可合理处理的数量。
4. gateway 连接 API internal SSE 时传 `X-AgentYard-Max-Final-Replay-Frames = maxPendingFinals - currentPendingFinals`；如果剩余容量小于等于 0，gateway 不建立 upstream SSE。
5. pending final 达到高水位时，gateway 取消 upstream API SSE；ACK 推进后 pending 降到低水位以下，再用最新 `lastAckedFinalSequence` 重新连接 API。
6. gateway-native provider 默认 `maxPendingFinals=1`，即同一 profile consumer 下串行发送：取一个 final、native send 成功或幂等确认、推进 checkpoint、再取下一个 final。
7. durable replay 未追平或 pending final 达到高水位期间，transient frame 可以直接丢弃；final recovery 正确性优先于临时体验完整性。
8. API 发送 `final-replay-window-exhausted` 后正常结束连接，gateway 不把这视为错误重连风暴，而是等待 pending 低水位或下游重连后继续拉取。

## 13. 安全边界

1. API internal SSE 只接受 internal bearer token。
2. extension-facing SSE 和 ACK 必须校验：
   - extension bearer token。当前可沿用 registration auth 的 `INTERNAL_TOKEN`，后续如引入独立 extension token，必须先更新 registration auth model。
   - registrationId
   - descriptor type/id
   - providerType 与 channel profile 匹配
   - channel profile ACTIVE
3. extension stream 只能订阅自己 providerType/registrationId 暴露的 profile。
4. frame payload 不包含 secret、account credential、raw provider token，也不包含 credential reference。
5. customer-visible frame 只允许客户可见文本和 canonical message block。

## 14. 前面 review findings 的落点

### Finding 1: API 缺少 profile/session 绑定来源

落点：API 在 PostgreSQL 维护 `channel_session_binding_snapshot`。ACTIVE binding 对 `sessionId` 是一对一；同步来源是启动全量拉取、profile 变更触发 refresh、gateway binding/profile 变更 hint、按 session 缺失兜底 refresh、定时 reconcile；多实例下通过 shared refresh request + keyed single-flight 避免重复回源。

### Finding 2: Final delivery 可靠性缺少 API outbox

落点：不建 API outbox。`FINAL_DELIVERY` 是 message-level derived frame，由 core DB durable session message + binding snapshot 派生。channel-gateway 维护 ACK 后的 `lastAckedFinalSequence`，恢复时从该 sequence 之后的下一条 final message 开始续接，中间 draft 丢弃。

### Finding 3: Extension 如何发现并维护 profile stream 未定义

落点：channel-gateway 增加 extension bootstrap endpoint。extension 启动后按 `registrationId / providerType` 拉取可订阅 ACTIVE profiles、当前 gateway checkpoint，并为每个 profile 稳定维护 SSE。

### Finding 4: Remote extension 凭证上下文被切断

落点：不在 outbound frame 下发 credential reference。remote extension provider 自己管理外部平台 credential；core 如需参与外部平台调用，只能通过独立 API 做受控中转。

### Finding 5: DRAFT_COMPLETE 与 FINAL_DELIVERY 可能双发

落点：`DRAFT_COMPLETE` 只结束 transient draft，不代表正式外部消息发送。`FINAL_DELIVERY` 是唯一正式外部消息发送动作。

### Finding 6: Coalescing 与 frameId 稳定性冲突

落点：不做短窗口合并。transient frame 逐 frame SSE，draft 不参与 durable recovery，断线后不从中间补 draft。

## 15. 实施阶段

实施边界：

1. Phase 3-6 是同一个 outbound frame stream cutover 的内部拆分，不是可以分别上线的 release checkpoint。
2. 不允许在 gateway relay、extension-facing SSE/ACK、provider adapter 尚未可端到端工作时，单独删除 API 到 gateway 的旧 outbound POST 路径。
3. 如果需要拆成多个 PR，前置 PR 只能落 contract、schema、repository、内部 helper 和不切流量的代码；最终 cutover PR 必须同时完成 API publisher、gateway upstream relay、extension-facing SSE/ACK、provider adapter，并删除旧 `sendOutboundActivity / deliverOutbound / sendActivity / sendOutbound` 路径。
4. cutover 验收以端到端 channel outbound 成功为准，不能只以 Phase 3 或 Phase 4 局部测试通过为准。

### Phase 1: 协议与契约

1. 在 `packages/contracts-jvm` 增加 `ChannelOutboundFrame`、`ChannelOutboundFrameKind`。
2. 在 `packages/contracts-jvm` 增加 `ChannelOutboundFrameAck` 与 `finalSequence` value contract。
3. 在 `packages/contracts` 增加对应 TypeScript contract。
4. 在 `packages/contracts/openapi/control-plane.yaml` 增加 API internal SSE endpoint 与 binding snapshot refresh endpoint。
5. 在 `packages/extension-protocol` 增加 extension-facing stream、ACK、bootstrap endpoint 与 JSON schema。
6. 更新 extension SDK JVM/Python 的协议常量和 contract tests。

验收：

1. contract tests 覆盖 frame schema、非法 kind/payload 组合。
2. `frameId`、`streamCursor`、`finalSequence`、`lastAckedFinalSequence` 的语义在 OpenAPI description 中明确。
3. `DRAFT_COMPLETE` 与 `FINAL_DELIVERY` 的外部语义在 extension protocol 中明确。
4. ACK schema 禁止 raw provider response 和 credential 字段。

### Phase 2: API binding snapshot

1. 新增 API PostgreSQL `channel_session_binding_snapshot` 与 repository。
2. 新增 channel-gateway binding snapshot 拉取 client，支持 full scan、updatedAfter tombstone、by-session。
3. 新增 gateway -> API binding snapshot refresh hint endpoint。
4. gateway 在 binding attach session 持久化完成后调用 refresh hint。
5. 新增 API binding snapshot refresh coordinator：shared refresh request、keyed single-flight lease、debounce、invalidation/version event。
6. API 支持启动全量、profile 变更触发 refresh、gateway hint refresh、按 session miss refresh、定时 reconcile。
7. outbound frame 发布前从 snapshot 解析唯一 `channelProfileId / externalConversationId / assistantId / customerId`。

验收：

1. API 重启后会从 channel-gateway 重建 binding snapshot。
2. profile disabled/deleted 后，本地 snapshot 不再用于 outbound frame 发布。
3. snapshot 缺失时会触发一次 by-session refresh，仍缺失则跳过并打 structured log。
4. 新 channel inbound 创建 session 后，gateway attach session 再通知 API，API snapshot 能立即查到该 session binding。
5. `attachSession` 完成前，该 session 不会产生可外发 `FINAL_DELIVERY`。
6. checkpoint 为空时，API 从该 profile 下所有 ACTIVE binding 可见的最早 durable final replay，不需要额外 outbound start sequence。
7. 同一 session 出现多个 ACTIVE binding 时，API fail closed，不发送 outbound，并记录数据完整性错误。
8. 多 API 实例同时收到同一 refresh request 时，只有一个实例回源 channel-gateway 并写 PostgreSQL。
9. 发起 hint 的 API 实例收到自己的 broadcast event 时，也必须走 keyed lease，不重复刷新。
10. `updatedAfter` 返回 disabled/deleted/detached tombstone，API 能清理旧 snapshot。
11. full reconcile 会把 gateway ACTIVE 集合中不存在的本地 snapshot 标记 inactive。

### Phase 3: API frame publisher

1. 新增 `session_runtime_message.final_sequence bigint not null`，由 PostgreSQL sequence 生成全局单调 final sequence；shared persistence/worker 写入 session message 时不手写该值。
2. 为 `final_sequence` 增加唯一索引和 replay 查询索引；final replay 查询按 `final_sequence` 排序，不按 `created_at` 排序。
3. 新增 `ChannelOutboundFramePublisher`。
4. `DefaultSessionChannelActivityRelay` 改为基于本地 snapshot 发布 transient frame。
5. `SessionChannelOutboundRelay` 不再同步调用 channel-gateway final delivery。
6. 新增 final frame 派生器，从 core DB session messages 生成 `FINAL_DELIVERY`。
7. 在 cutover 中删除 API 到 channel-gateway 的逐 frame `sendOutboundActivity` 调用。
8. 在 cutover 中删除 API 到 channel-gateway 的同步 final `deliverOutbound` 调用。
9. 在 cutover 中删除 `/api/internal/session-runtime/sessions/{sessionId}/channel-outbound/replay` 旧同步 replay 入口。
10. 实现 per-profile SSE emitter、Redis broadcast、transient replay buffer。

验收：

1. `REPLY_BLOCK_DELTA` 不再产生 `POST /internal/channel-outbound/activities`。
2. durable assistant/human/system final message 可被派生为稳定 `FINAL_DELIVERY`。
3. `Last-Event-ID` miss 时不补 draft，只从 `lastAckedFinalSequence` 后补 final。
4. API 按 `X-AgentYard-Max-Final-Replay-Frames` 限制单次 durable final replay，并在窗口耗尽时发送 `final-replay-window-exhausted`。
5. API 双实例下，gateway 连接任一实例都能收到 profile frame。
6. final frame 按 `finalSequence` 全局稳定排序。
7. final replay 不依赖 `createdAt`，不受时间回填、导入或时钟漂移影响。

### Phase 4: channel-gateway upstream relay

1. 新增 `channel_outbound_final_checkpoint`，删除旧 `channel_outbound_delivery` delivery ledger。
2. 为 ACTIVE profile consumer 建立 API internal SSE subscriber。
3. 增加基于 `channelProfileId + providerType + consumerKind + consumerId` 的 profile consumer ownership lock。
4. 按 provider capability filter frames。
5. checkpoint 表使用非空 `consumer_id` 和唯一键 `(channel_profile_id, provider_type, consumer_kind, consumer_id)`。
6. 维护运行态 `upstreamStreamCursor / lastAckedFinalSequence`。
7. 不新增 final frame payload / provider result 持久化表。

验收：

1. 多个 gateway 实例只有一个订阅同一 profile consumer。
2. owner 切换后使用 checkpoint 表里的 `lastAckedFinalSequence` 续订，不重复正式外部消息。
3. API stream 断线恢复后，不补过期 draft，但能补 final delivery。
4. pending final 达到高水位时，gateway 会取消 upstream SSE；降到低水位后再重新连接 API 拉下一批。
5. 同一 ACTIVE profile 不会同时启动 `REMOTE_EXTENSION` 和 `GATEWAY_NATIVE` outbound consumer。
6. gateway-native provider 没有 `registrationId` 时仍能用稳定 `consumer_id` 维护 checkpoint。
7. 旧 outbound delivery admin/API/UI surface 被删除或替换为 checkpoint 观测。

### Phase 5: extension-facing SSE 与 bootstrap

1. 新增 `/extension/channel/outbound-frame-subscriptions`。
2. 新增 `/extension/channel/outbound-frames/stream`。
3. 新增 `/extension/channel/outbound-frames/ack`。
4. 实现 profile consumer lease，避免多个 extension consumer 重复消费同一 profile。
5. 实现 Redis forwarded pending state + TTL，key 使用完整 profile consumer identity，ACK 只能推进已下发且位于 pending 队首的 final。
6. 实现 `Last-Event-ID` transient replay。
7. 实现基于 gateway checkpoint 的 final checkpoint 续接。
8. extension reconnect 时，channel-gateway 将 `lastAckedFinalSequence` 穿透给 API，由 API 从 core DB 派生 final replay。

验收：

1. extension 启动后能发现自己可订阅的 ACTIVE profiles。
2. extension 断线后可用 gateway 持久化 checkpoint 续收 final。
3. 同 profile 第二个 extension stream 被拒绝或明确替换旧连接。
4. bootstrap 只返回 remote extension 可以消费的 profiles，不暴露 gateway-native profiles。
5. channel-gateway 重启或 extension 长时间离线后，不依赖 gateway 本地 backlog 也能补 final。
6. 未 ACK final 在重连后会重放；已 ACK final 不会重放。
7. extension ACK 未下发过、非 pending 队首或 forwarded marker 已过期的 future final 时，gateway 不推进 checkpoint。
8. forwarded marker 和 pending list 默认 TTL 5 分钟，provider 可配置但最大建议 15 分钟。
9. remote extension 没有 active downstream SSE 时，gateway 不预消费 API final replay。
10. extension 收到 ACK 409/422 后会主动重连，gateway 基于 checkpoint 重新订阅 API replay。

### Phase 6: provider adapter 重构

1. remote extension provider 改为 frame stream client。
2. gateway-native provider 改为消费同一 `ChannelOutboundFrame` interface，并对同一 profile consumer 串行发送 final，发送成功后按顺序推进 checkpoint。
3. Feishu native provider 先实现 `FINAL_DELIVERY`；typing/draft 是否支持由 capability 明确声明。
4. 删除 `sendActivity` adapter 接口。
5. 删除 remote `sendOutbound` 同步调用路径。
6. gateway-native relay supervisor 只启动 gateway-native profiles；remote extension profiles 只通过 extension-facing SSE 消费。
7. remote provider 必须实现 `frameId` 幂等。

验收：

1. TEXT final message 可通过 frame stream 完成发送。
2. 不支持 draft 的 provider 不收到 draft frames。
3. 不支持 typing 的 provider 不收到 typing frames。
4. provider 重复收到同一 `FINAL_DELIVERY frameId` 不重复发送外部消息。
5. remote extension 只有在 ACK 后才推进 gateway checkpoint。
6. gateway-native provider 不会因为多个 session 并发发送而越序推进 profile consumer checkpoint。
7. 同一 profile 的 remote/native consumer 互斥有测试覆盖。

### Phase 7: 观测和故障注入

新增 metrics：

```text
agentyard.channel_outbound.api_stream.connected
agentyard.channel_outbound.api_stream.reconnect
agentyard.channel_outbound.extension_stream.connected
agentyard.channel_outbound.extension_stream.reconnect
agentyard.channel_outbound.frame.emitted
agentyard.channel_outbound.frame.transient_expired
agentyard.channel_outbound.final.derived
agentyard.channel_outbound.final.duplicate_seen
agentyard.channel_outbound.final.ack
agentyard.channel_outbound.final.ack_forwarded_marker_miss
agentyard.channel_outbound.final.ack_pending_order_rejected
agentyard.channel_outbound.final.replayed
```

新增 structured logs 字段：

```text
channelProfileId
providerType
consumerKind
consumerId
frameId
streamCursor
finalSequence
lastAckedFinalSequence
kind
sessionId
turnId
turnExecutionId
externalConversationId
```

故障注入：

1. API SSE 中断。
2. channel-gateway owner 切换。
3. extension SSE 中断。
4. high-frequency draft delta。
5. transient replay buffer miss。
6. extension 重复收到同一 final frame。
7. extension 收到 final 后发送成功但 ACK 前崩溃。
8. gateway checkpoint 表落后于 extension 本地幂等记录。
9. API internal SSE live idle 超过常见代理 idle timeout，确认 heartbeat comment 能保持连接且不改变 replay/checkpoint 语义。
10. forwarded marker TTL 过期后 extension 才 ACK。
11. binding disabled/deleted tombstone 丢失后由 full reconcile 清理。
12. extension 先 ACK 后续 final、前序 final 未 ACK。
13. gateway-native provider 同一 profile consumer 下多个 session final 并发到达。

## 16. 最终验收标准

1. API 到 channel-gateway 不再对每个 transient frame 发 HTTP POST。
2. channel-gateway 到 extension 不再通过 `sendActivity` / `sendOutbound` 同步 POST 投递 outbound。
3. ACTIVE channel profile 的 active consumer 会稳定维护 outbound frame stream。
4. playbook、人工回复、session replay、普通 agent reply 产生的 outbound 都能进入同一 frame stream。
5. extension 断线重连后不补过期 draft，只从下一个 final checkpoint 续接。
6. transient frame 可过期丢弃，final delivery 可从 core DB 派生恢复。
7. 多 API / 多 channel-gateway 实例下不会重复消费同一 profile consumer。
8. 多 extension 实例下不会因为多个 SSE consumer 重复发送外部消息。
9. frame 协议、bootstrap 协议和 capability schema 有 contract tests。
10. final ACK checkpoint 表只保存恢复进度，不保存 provider result ledger。
11. Feishu native provider 和至少一个 remote extension provider 有端到端测试覆盖。
