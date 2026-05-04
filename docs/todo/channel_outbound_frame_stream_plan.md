# Channel Outbound Frame Stream 改造方案

## 1. 目标结论

当前 `API -> channel-gateway -> extension channel provider` 的 outbound 链路要从“每个 frame 一次 HTTP POST”改成“按 channel profile 稳定维护 outbound frame stream”。

目标形态：

```text
API
  -- internal SSE: profile-level outbound frame stream -->
channel-gateway
  -- extension-facing SSE: profile-level outbound frame stream -->
extension channel provider
```

核心决策：

1. inbound 事件提交不复用为 outbound SSE。inbound 仍然快速 ACK。
2. 两段 SSE 都按 `channelProfileId` 维度维护长连接。
3. transient frame 只服务实时体验，短 TTL，断线后可以丢弃。
4. `FINAL_DELIVERY` 不进入 API outbox，也不由 channel-gateway 持久化 result；它从 core DB 的 durable session message 派生。
5. 恢复时从“下一个 final checkpoint”开始续接，不补中间 draft。
6. channel-gateway 不承担业务持久化账本，只做 profile stream relay、连接治理和 capability filter。
7. 去重责任由稳定 `frameId / idempotencyKey` 承担，extension/provider 必须按 `frameId` 幂等。
8. 不做“旧 POST activity 先保留、后续逐步切换”的兼容路线。实现时直接把 channel outbound 实时链路重构到 frame stream。

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

恢复语义：

```text
live stream:
draft...draft -> FINAL_DELIVERY(db) -> draft...draft -> FINAL_DELIVERY(db)

recovery:
从 lastSeenFinal 之后的下一个 FINAL_DELIVERY 开始补
中间所有 draft 直接丢弃
```

断线重连后：

1. 如果 transient replay buffer 仍命中，可以补近期 transient frame。
2. 如果 transient replay buffer miss，不补 draft，不从中间继续 draft。
3. 如果某个 turn 正在生成中且 draft 前缀已错过，等这个 turn 的 `FINAL_DELIVERY`。
4. final replay 只从 durable final cursor 后的下一条 final message 开始派生。

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
lynxus.channel-outbound-frame.v1
```

frame body：

```json
{
  "protocol": "lynxus.channel-outbound-frame.v1",
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
  "idempotencyKey": "channel-profile-1:exec-1:7:DRAFT_UPDATE",
  "credentialRef": "vault://channel-profile-1",
  "payload": {
    "messageId": "session-message-reply-1",
    "blockId": "reply-block-1",
    "blockType": "TEXT",
    "delta": "hello"
  }
}
```

field 规则：

1. `credentialRef` 是可选的凭证引用，不是 raw secret。
2. remote extension provider 需要调用外部平台时，必须通过 `credentialRef` 或等价 scoped credential context 解析凭证。
3. gateway-native provider 可以忽略 `credentialRef`，在 channel-gateway 内部按 profile/account 解析凭证。
4. frame 不包含 raw token、account secret、provider native secret。

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
2. coalesced draft frame：`{channelProfileId}:{turnExecutionId}:{firstSourceSeq}-{lastSourceSeq}:DRAFT_UPDATE`。
3. final delivery：`{channelProfileId}:{sessionId}:{sessionMessageId}:{blockIndex}:FINAL_DELIVERY`。
4. 同一个业务动作重放时必须生成相同 `frameId`。
5. extension 必须把 `frameId` 当作幂等键。重复收到同一个 `frameId` 时不得重复发送外部消息。

payload 约定：

1. `TYPING_START / TYPING_STOP`
   - 必须带 `messageId`。
   - 可以不带 block 字段。
2. `DRAFT_UPDATE`
   - 必须带 `messageId / blockId / blockType`。
   - `TEXT` block 必须带 `delta`。
   - 如果合并多个 delta，`delta` 是合并后的增量文本。
3. `DRAFT_COMPLETE`
   - 必须带 `messageId / blockId / block`。
   - `block` 是 canonical session message block。
   - 只表示 transient draft 结束，不代表正式外部消息已经发送。
4. `DRAFT_DISCARD`
   - 必须带 `messageId`。
   - 可以带 `reason`。
5. `FINAL_DELIVERY`
   - 必须带 `sessionMessageId / blockIndex / messageBlock`。
   - 可以带 `resolvedTemplate`。
   - `messageBlock` 保持 `TEXT / IMAGE / RICH_TEXT / CARD` canonical 结构，不把 provider native payload 泄露回 session 模型。

## 5. Draft 与 Final 的外部语义

为避免双发，必须固定以下语义：

1. `FINAL_DELIVERY` 是唯一正式外部消息发送动作。
2. `DRAFT_UPDATE / DRAFT_COMPLETE / DRAFT_DISCARD` 只表示临时体验。
3. 对支持 draft 的 provider，`DRAFT_COMPLETE` 只能完成或关闭临时草稿态，不能再发送一条正式消息。
4. 对不支持 draft 的 provider，channel-gateway 不下发 `DRAFT_*`。
5. 无论 provider 是否支持 draft，最终外部可见消息都由 `FINAL_DELIVERY` 触发。

## 6. API Binding Snapshot

API 需要按 profile 发布 outbound frame，但 channel-gateway 仍是 `ChannelConversationBinding` 的权威存储。API 维护只读 snapshot：

```text
channel_session_binding_snapshot
  session_id
  channel_profile_id
  provider_type
  external_conversation_id
  assistant_id
  customer_id
  binding_status
  profile_status
  updated_at
```

同步规则：

1. API 启动后全量分页拉取 channel-gateway 的 ACTIVE bindings。
2. profile create/update/disable/delete 由 API 中转时，API 对对应 `channelProfileId` 触发 snapshot refresh。
3. 新增 binding 由 channel-gateway 在 inbound ingest/dispatch 中创建；dispatch 经过 API 后，API 不从 inbound request 手写 upsert 字段，而是触发对应 profile 的 binding snapshot refresh。
4. API 增加定时同步兜底，按 `updatedAfter` 增量拉取；如果 channel-gateway 暂时不支持增量查询，则先用全量分页 reconcile。
5. profile disabled/deleted 时，API 删除或标记该 profile 下 snapshot inactive。
6. binding status 非 ACTIVE 或 `sessionId` 为空时，不参与 outbound frame lookup。
7. outbound frame 发布前，如果本地 snapshot 缺失，允许触发一次 profile refresh 后再判定；仍缺失则跳过 channel outbound，并记录 structured log。

## 7. API -> channel-gateway internal SSE

API 新增 internal endpoint：

```http
GET /api/internal/channel-outbound/frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {internal-token}
Last-Event-ID: {streamCursor}
X-Lynxus-Last-Final-Frame-Id: {lastSeenFinalFrameId}
```

SSE event：

```text
id: 42
event: channel-outbound-frame
data: {"protocol":"lynxus.channel-outbound-frame.v1", "...": "..."}
```

cursor 规则：

1. SSE `id` 是短期 `streamCursor`，用于 transient replay。
2. `streamCursor` 可以过期。
3. `lastSeenFinalFrameId` 是 final checkpoint，用于 durable final replay。
4. 如果 `Last-Event-ID` 命中 transient replay buffer，API 可以补近期 transient frames。
5. 如果 `Last-Event-ID` miss，API 直接跳过 transient frames。
6. API 总是从 `lastSeenFinalFrameId` 之后的 durable final message 开始派生 `FINAL_DELIVERY`。

API 侧职责：

1. `SessionRuntimeStreamService.acceptStreamFrame` 不再直接触发 `ChannelGatewayClient.sendOutboundActivity`。
2. `SessionChannelActivityRelay` 改为基于 binding snapshot 写入 `ChannelOutboundFramePublisher`。
3. `SessionChannelOutboundRelay` 不再直接调用 `ChannelGatewayClient.deliverOutbound`，而是让 durable session message 可被 `FINAL_DELIVERY` 派生器读取。
4. API 对每个 `channelProfileId` 维护短期 replay buffer，用于 transient frames。
5. API 提供 final frame 派生器，从 core DB session messages + binding snapshot 生成 `FINAL_DELIVERY`。
6. API 多实例下，transient frame publish 通过 Redis Pub/Sub 广播给本实例 SSE subscriber。

API 不做：

1. 不维护 channel outbound final outbox。
2. 不持久化 provider delivery result。
3. 不保证 transient frame 完整恢复。

## 8. channel-gateway profile relay

channel-gateway 后台维护 profile 级订阅：

```text
ACTIVE channel profile
  -> gateway instance 获取 profile stream ownership
  -> 连接 API internal SSE
  -> capability filter
  -> 发布给 extension-facing SSE subscriber
```

多实例规则：

1. 同一个 `channelProfileId` 同一时间只能有一个 channel-gateway instance 订阅 API internal SSE。
2. 使用 Redis lock 或 PostgreSQL advisory lock 实现 `channel-outbound-api-stream-owner:{channelProfileId}`。
3. owner 失联后 lock 过期，其他 gateway instance 接管。
4. 接管时使用本地连接状态中的 `Last-Event-ID` 和 `lastSeenFinalFrameId` 重新连接 API。

channel-gateway 不做：

1. 不持久化 final frame。
2. 不持久化 final result。
3. 不维护 delivery ledger。
4. 不把 transient frame 作为 durable fact。

downstream 恢复规则：

1. extension 断线期间，channel-gateway 可能继续从 API 收到 frame，但不为该 extension 持久化 backlog。
2. extension 重连时带上自己的 `lastSeenFinalFrameId`。
3. channel-gateway 必须把该 checkpoint 穿透给 API，要求 API 从 core DB 派生该 checkpoint 之后的 `FINAL_DELIVERY`。
4. 因此 extension-facing final recovery 不依赖 channel-gateway 本地 buffer。
5. transient frame 仍只按 channel-gateway 当前内存/短期 replay 能力尽量补，miss 后直接跳过。

channel-gateway 可以维护的运行态状态：

```text
channelProfileId
upstreamStreamCursor
lastSeenFinalFrameId
downstreamStreamCursor
extensionConsumerId
connectedAt
lastHeartbeatAt
```

这些状态可以是内存 + Redis lease，不是业务持久化账本。

## 9. channel-gateway -> extension SSE

extension 面向 channel-gateway 建立 profile 级长连接：

```http
GET /extension/channel/outbound-frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {extension-token}
X-Lynxus-Extension-Registration-Id: {registrationId}
X-Lynxus-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-Lynxus-Extension-Descriptor-Id: {providerType}
Last-Event-ID: {streamCursor}
X-Lynxus-Last-Final-Frame-Id: {lastSeenFinalFrameId}
```

SSE event：

```text
id: 105
event: channel-outbound-frame
data: {"protocol":"lynxus.channel-outbound-frame.v1", "frameId":"...", "...":"..."}
```

连接规则：

1. extension client 应在 channel profile ACTIVE 后连接并稳定维护。
2. extension client 不根据 inbound、playbook、session 或 turn 判断何时连接。
3. 同一个 `channelProfileId + registrationId + providerType` 同一时间只允许一个 active extension stream consumer。
4. 如果第二个 consumer 连接，推荐返回 `409 CONFLICT`；如果选择踢掉旧连接，必须记录 structured log。
5. SSE heartbeat 使用 comment，间隔建议 15-30 秒。

extension checkpoint 规则：

1. extension 必须在成功幂等处理 `FINAL_DELIVERY` 后，才能推进本地 `lastSeenFinalFrameId`。
2. 如果收到 final 后进程崩溃、外部平台发送失败或本地幂等记录未落盘，下次重连必须继续请求同一个 final。
3. `Last-Event-ID` 只代表短期 stream cursor，不能替代 `lastSeenFinalFrameId`。
4. 对 transient frame，extension 不需要维护 durable checkpoint。

extension discovery：

1. channel-gateway 提供 bootstrap endpoint，供 extension 查询自己可订阅的 ACTIVE profiles。
2. bootstrap 必须按 `registrationId / providerType` 授权过滤。
3. bootstrap response 至少包含 `channelProfileId / providerType / status / streamUrl / revision / updatedAt`。
4. extension 启动后先调用 bootstrap，再为每个 ACTIVE profile 建立 SSE。
5. extension 定时重新 bootstrap，发现新增 profile 时建立连接，发现 disabled/deleted profile 时关闭连接。

bootstrap endpoint：

```http
GET /extension/channel/outbound-frame-subscriptions
Authorization: Bearer {extension-token}
X-Lynxus-Extension-Registration-Id: {registrationId}
X-Lynxus-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-Lynxus-Extension-Descriptor-Id: {providerType}
```

## 10. Extension 幂等与 provider 发送

extension 必须实现 `frameId` 幂等。

`FINAL_DELIVERY frameId` 必须稳定派生：

```text
{channelProfileId}:{sessionId}:{sessionMessageId}:{blockIndex}:FINAL_DELIVERY
```

extension 收到重复 `FINAL_DELIVERY`：

1. 如果本地已处理过该 `frameId`，直接 no-op。
2. 如果 provider 支持 idempotency key，把 `frameId` 透传给 provider。
3. 如果 provider 不支持幂等，extension 自己必须持久化 `frameId -> externalMessageId`。
4. 如果 extension 无法提供幂等能力，该 provider 不能声明 `supportsFinalDelivery=true`。

remote extension 凭证：

1. `credentialRef` 是 extension-facing frame 的可选字段。
2. `credentialRef` 只表示受控凭证引用，不暴露 raw secret。
3. extension 解析凭证时必须走现有 credential lifecycle 或等价的 secret backend。
4. credential scope 必须绑定 `registrationId / providerType / channelProfileId`。

## 11. Capability 与 manifest 改造

channel provider descriptor 的 outbound 能力收敛为明确对象：

```json
{
  "outbound": {
    "mode": "FRAME_STREAM",
    "supportsTyping": true,
    "supportsDraftUpdate": true,
    "supportsFinalDelivery": true,
    "supportsCredentialRef": true,
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
6. remote provider 如果需要外部平台凭证，必须声明 `supportsCredentialRef=true`。

## 12. Backpressure 与合并

不能把每个模型 token 无限制推给 extension。

API 发布侧：

1. 对 `DRAFT_UPDATE` 做 profile/session/turn/block 维度短窗口合并。
2. 默认合并窗口 100ms。
3. 合并后 frameId 使用 `firstSourceSeq-lastSourceSeq`，避免重放时幂等键漂移。
4. customer-visible 校验仍在 API 边界完成，不能把 INTERNAL/DEVELOPER frame 投到 channel outbound frame。

channel-gateway 下发侧：

1. 如果 extension SSE consumer 落后太多，transient frame 可以过期丢弃。
2. `FINAL_DELIVERY` 不允许因 backpressure 丢弃，但可以从 API durable final 派生器重新获取。
3. 同一个 final delivery 的重发必须使用同一个 `frameId` 和 `Idempotency-Key`。

## 13. 安全边界

1. API internal SSE 只接受 internal bearer token。
2. extension-facing SSE 必须校验：
   - extension token
   - registrationId
   - descriptor type/id
   - providerType 与 channel profile 匹配
   - channel profile ACTIVE
3. extension stream 只能订阅自己 providerType/registrationId 暴露的 profile。
4. frame payload 不包含 secret、account credential、raw provider token。
5. `credentialRef` 必须是 scoped reference，不是 secret value。
6. customer-visible frame 只允许客户可见文本和 canonical message block。

## 14. 前面 review findings 的落点

### Finding 1: API 缺少 profile/session 绑定来源

落点：API 维护 `channel_session_binding_snapshot`。同步来源是启动全量拉取、profile 变更触发 refresh、inbound dispatch 后按 profile refresh、定时 reconcile 兜底。

### Finding 2: Final delivery 可靠性缺少 API outbox

落点：不建 API outbox。`FINAL_DELIVERY` 是 derived frame，由 core DB durable session message + binding snapshot 派生。恢复时从 `lastSeenFinalFrameId` 之后的下一条 final message 开始续接，中间 draft 丢弃。

### Finding 3: Extension 如何发现并维护 profile stream 未定义

落点：channel-gateway 增加 extension bootstrap endpoint。extension 启动后按 `registrationId / providerType` 拉取可订阅 ACTIVE profiles，并为每个 profile 稳定维护 SSE。

### Finding 4: Remote extension 凭证上下文被切断

落点：frame 增加可选 `credentialRef`。它是 scoped credential reference，不是 raw secret。remote extension 需要凭证时按 `registrationId / providerType / channelProfileId` scope 解析。

### Finding 5: DRAFT_COMPLETE 与 FINAL_DELIVERY 可能双发

落点：`DRAFT_COMPLETE` 只结束 transient draft，不代表正式外部消息发送。`FINAL_DELIVERY` 是唯一正式外部消息发送动作。

### Finding 6: Coalescing 与 frameId 稳定性冲突

落点：coalesced draft frame 使用 `firstSourceSeq-lastSourceSeq` 生成 frameId；draft 不参与 durable recovery，断线后不从中间补 draft。

## 15. 实施阶段

### Phase 1: 协议与契约

1. 在 `packages/contracts-jvm` 增加 `ChannelOutboundFrame`、`ChannelOutboundFrameKind`。
2. 在 `packages/contracts` 增加对应 TypeScript contract。
3. 在 `packages/contracts/openapi/control-plane.yaml` 增加 API internal SSE endpoint。
4. 在 `packages/extension-protocol` 增加 extension-facing stream OpenAPI、bootstrap endpoint 与 JSON schema。
5. 更新 extension SDK JVM/Python 的协议常量和 contract tests。

验收：

1. contract tests 覆盖 frame schema、非法 kind/payload 组合。
2. `frameId`、`streamCursor`、`lastSeenFinalFrameId` 的语义在 OpenAPI description 中明确。
3. `DRAFT_COMPLETE` 与 `FINAL_DELIVERY` 的外部语义在 extension protocol 中明确。

### Phase 2: API binding snapshot

1. 新增 API 本地 `channel_session_binding_snapshot` 与 repository。
2. 新增 channel-gateway binding snapshot 拉取 client。
3. 支持启动全量、profile 变更触发 refresh、inbound dispatch 后按 profile refresh、定时 reconcile。
4. outbound frame 发布前从 snapshot 解析 `channelProfileId / externalConversationId / assistantId / customerId`。

验收：

1. API 重启后会从 channel-gateway 重建 binding snapshot。
2. profile disabled/deleted 后，本地 snapshot 不再用于 outbound frame 发布。
3. snapshot 缺失时会触发一次 profile refresh，仍缺失则跳过并打 structured log。

### Phase 3: API frame publisher

1. 新增 `ChannelOutboundFramePublisher`。
2. `DefaultSessionChannelActivityRelay` 改为基于本地 snapshot 发布 transient frame。
3. `SessionChannelOutboundRelay` 不再同步调用 channel-gateway final delivery。
4. 新增 final frame 派生器，从 core DB session messages 生成 `FINAL_DELIVERY`。
5. 删除 API 到 channel-gateway 的逐 frame `sendOutboundActivity` 调用。
6. 删除 API 到 channel-gateway 的同步 final `deliverOutbound` 调用。
7. 实现 per-profile SSE emitter、Redis broadcast、transient replay buffer。

验收：

1. `REPLY_BLOCK_DELTA` 不再产生 `POST /internal/channel-outbound/activities`。
2. durable assistant/human/system final message 可被派生为稳定 `FINAL_DELIVERY`。
3. `Last-Event-ID` miss 时不补 draft，只从 `lastSeenFinalFrameId` 后补 final。
4. API 双实例下，gateway 连接任一实例都能收到 profile frame。

### Phase 4: channel-gateway upstream relay

1. 为 ACTIVE profile 建立 API internal SSE subscriber。
2. 增加 profile ownership lock。
3. 按 provider capability filter frames。
4. 维护运行态 `upstreamStreamCursor / lastSeenFinalFrameId`。
5. 不新增 final frame/result 持久化表。

验收：

1. 多个 gateway 实例只有一个订阅同一 profile。
2. owner 切换后使用 `lastSeenFinalFrameId` 续订，不重复正式外部消息。
3. API stream 断线恢复后，不补过期 draft，但能补 final delivery。

### Phase 5: extension-facing SSE 与 bootstrap

1. 新增 `/extension/channel/outbound-frame-subscriptions`。
2. 新增 `/extension/channel/outbound-frames/stream`。
3. 实现 profile consumer lease，避免多个 extension consumer 重复消费同一 profile。
4. 实现 `Last-Event-ID` transient replay。
5. 实现 `X-Lynxus-Last-Final-Frame-Id` final checkpoint 续接。
6. extension reconnect 时，channel-gateway 将 final checkpoint 穿透给 API，由 API 从 core DB 派生 final replay。

验收：

1. extension 启动后能发现自己可订阅的 ACTIVE profiles。
2. extension 断线后可用 `lastSeenFinalFrameId` 续收 final。
3. 同 profile 第二个 extension stream 被拒绝或明确替换旧连接。
4. channel-gateway 重启或 extension 长时间离线后，不依赖 gateway 本地 backlog 也能补 final。

### Phase 6: provider adapter 重构

1. remote extension provider 改为 frame stream client。
2. gateway-native provider 改为消费同一 `ChannelOutboundFrame` interface。
3. Feishu native provider 先实现 `FINAL_DELIVERY`；typing/draft 是否支持由 capability 明确声明。
4. 删除 `sendActivity` adapter 接口。
5. 删除 remote `sendOutbound` 同步调用路径。
6. remote provider 必须实现 `frameId` 幂等。

验收：

1. TEXT final message 可通过 frame stream 完成发送。
2. 不支持 draft 的 provider 不收到 draft frames。
3. 不支持 typing 的 provider 不收到 typing frames。
4. provider 重复收到同一 `FINAL_DELIVERY frameId` 不重复发送外部消息。

### Phase 7: 观测和故障注入

新增 metrics：

```text
lynxus.channel_outbound.api_stream.connected
lynxus.channel_outbound.api_stream.reconnect
lynxus.channel_outbound.extension_stream.connected
lynxus.channel_outbound.extension_stream.reconnect
lynxus.channel_outbound.frame.emitted
lynxus.channel_outbound.frame.transient_expired
lynxus.channel_outbound.final.derived
lynxus.channel_outbound.final.duplicate_seen
```

新增 structured logs 字段：

```text
channelProfileId
providerType
frameId
streamCursor
lastSeenFinalFrameId
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

## 16. 最终验收标准

1. API 到 channel-gateway 不再对每个 transient frame 发 HTTP POST。
2. channel-gateway 到 extension 不再通过 `sendActivity` / `sendOutbound` 同步 POST 投递 outbound。
3. ACTIVE channel profile 会稳定维护 outbound frame stream。
4. playbook、人工回复、session replay、普通 agent reply 产生的 outbound 都能进入同一 frame stream。
5. extension 断线重连后不补过期 draft，只从下一个 final checkpoint 续接。
6. transient frame 可过期丢弃，final delivery 可从 core DB 派生恢复。
7. 多 API / 多 channel-gateway 实例下不会重复消费同一 profile。
8. 多 extension 实例下不会因为多个 SSE consumer 重复发送外部消息。
9. frame 协议、bootstrap 协议和 capability schema 有 contract tests。
10. Feishu native provider 和至少一个 remote extension provider 有端到端测试覆盖。
