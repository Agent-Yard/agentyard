# Channel Outbound Frame Stream 改造方案

## 1. 结论

当前 `API -> channel-gateway -> extension channel provider` 的 outbound 链路要从“每个 frame 一次 HTTP POST”改成“按 channel profile 稳定维护 outbound frame stream”。

目标形态：

```text
API
  -- internal SSE: channel outbound frames by channelProfileId -->
channel-gateway
  -- extension-facing SSE: channel outbound frames by channelProfileId -->
extension channel provider
  -- HTTP result/ack -->
channel-gateway
```

核心决策：

1. inbound 事件提交不复用为 outbound SSE。inbound 仍然快速 ACK。
2. outbound stream 按 `channelProfileId` 维度维护长连接，不按 inbound message、session、turn 或 playbook 临时建连接。
3. SSE 的 `id` 使用单调 cursor，不直接使用业务 `frameId`；`frameId` 作为幂等键存在 frame body 里。
4. transient activity 和 final delivery 进入同一个 `ChannelOutboundFrame` 协议。
5. extension 通过独立 HTTP result endpoint 回传每个 frame 的处理结果；SSE 只负责下发，不承担双向确认。
6. 不做“旧 POST activity 先保留、后续逐步切换”的兼容路线。实现时直接把 channel outbound 实时链路重构到 frame stream。

## 2. 为什么不能复用 inbound 请求

inbound 是一次性事件提交，outbound 是长期订阅通道。两者生命周期不同：

1. inbound 必须快速返回，避免外部平台或 extension 重试。
2. outbound frame 可能来自 playbook、人工回复、session replay、主动通知，不一定由刚刚提交的 inbound 触发。
3. SSE 断线恢复依赖 `Last-Event-ID`，它应该绑定 profile 级 cursor，而不是某一次 inbound request。
4. 同一个 profile 下可能有多个 session/conversation 同时产生 outbound frame。

因此协议必须拆开：

```text
extension -> channel-gateway
POST /extension/channel/inbound-events

extension -> channel-gateway
GET /extension/channel/outbound-frames/stream?channelProfileId=...
```

## 3. Frame 协议

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
  "payload": {
    "messageId": "session-message-reply-1",
    "blockId": "reply-block-1",
    "blockType": "TEXT",
    "delta": "hello"
  }
}
```

`frameId` 规则：

1. transient frame：`{channelProfileId}:{turnExecutionId}:{sourceSeq}:{kind}`。
2. final delivery：`{channelProfileId}:{sessionId}:{sessionMessageId}:{blockIndex}:FINAL_DELIVERY`。
3. 同一个业务动作重放时必须生成相同 `frameId`。
4. extension 必须把 `frameId` 当作幂等键。重复收到同一个 `frameId` 时不得重复发送外部消息。

frame kinds：

```text
TYPING_START
TYPING_STOP
DRAFT_UPDATE
DRAFT_COMPLETE
DRAFT_DISCARD
FINAL_DELIVERY
```

payload 约定：

1. `TYPING_START / TYPING_STOP`
   - 必须带 `messageId`。
   - 可以不带 block 字段。
2. `DRAFT_UPDATE`
   - 必须带 `messageId / blockId / blockType`。
   - `TEXT` block 必须带 `delta`。
   - 后续如果要支持 full-so-far，可以加 `text`，但不要替代 `delta` 的幂等语义。
3. `DRAFT_COMPLETE`
   - 必须带 `messageId / blockId / block`。
   - `block` 是 canonical session message block。
4. `DRAFT_DISCARD`
   - 必须带 `messageId`。
   - 可以带 `reason`。
5. `FINAL_DELIVERY`
   - 必须带 `messageBlock`。
   - 可以带 `resolvedTemplate`。
   - `messageBlock` 保持 `TEXT / IMAGE / RICH_TEXT / CARD` canonical 结构，不把 provider native payload 泄露回 session 模型。

## 4. API -> channel-gateway internal SSE

API 新增 internal endpoint：

```http
GET /api/internal/channel-outbound/frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {internal-token}
Last-Event-ID: {sourceCursor}
```

SSE event：

```text
id: 42
event: channel-outbound-frame
data: {"protocol":"lynxus.channel-outbound-frame.v1", "...": "..."}
```

这里 `id` 是 API 在 `channelProfileId` 范围内的单调 `sourceCursor`，不是 `frameId`。

API 侧职责：

1. `SessionRuntimeStreamService.acceptStreamFrame` 不再直接触发 `ChannelGatewayClient.sendOutboundActivity`。
2. `SessionChannelActivityRelay` 改为写入 API 的 `ChannelOutboundFramePublisher`。
3. `SessionChannelOutboundRelay` 不再直接调用 `ChannelGatewayClient.deliverOutbound`，而是发布 `FINAL_DELIVERY` frame。
4. API 对每个 `channelProfileId` 维护短期 replay buffer，用于 transient frames。
5. final delivery frame 必须能从 durable session message 重建，不能只依赖短期 replay buffer。
6. API 多实例下，frame publish 需要通过 Redis Pub/Sub 广播给本实例 SSE subscriber，并使用共享 cursor 保证同 profile 内顺序。

API cursor 规则：

1. 每个 `channelProfileId` 单独递增。
2. SSE `Last-Event-ID` 表示“从这个 cursor 之后继续 replay”。
3. 如果 replay buffer 已过期：
   - transient frame 可以丢弃。
   - final delivery frame 必须通过 durable message/outbox 补发。
4. API 不能因为 channel-gateway 暂时断线而丢失 final delivery。

## 5. channel-gateway profile 订阅器

channel-gateway 后台维护 profile 级订阅：

```text
ACTIVE channel profile
  -> gateway instance 获取 profile stream ownership
  -> 连接 API internal SSE
  -> 持久化收到的 ChannelOutboundFrame
  -> 发布给 extension-facing SSE subscriber
```

多实例规则：

1. 同一个 `channelProfileId` 同一时间只能有一个 channel-gateway instance 订阅 API internal SSE。
2. 使用 Redis lock 或 PostgreSQL advisory lock 实现 `channel-outbound-api-stream-owner:{channelProfileId}`。
3. owner 失联后 lock 过期，其他 gateway instance 接管。
4. 接管时使用已持久化的 upstream cursor 作为 `Last-Event-ID` 重新连接 API。

channel-gateway 持久化表建议：

```text
channel_outbound_frame
  frame_id                 text primary key
  channel_profile_id        text not null
  provider_type             text not null
  external_conversation_id  text not null
  session_id                text null
  turn_id                   text null
  turn_execution_id         text null
  source_cursor             bigint not null
  profile_cursor            bigint not null
  kind                      text not null
  payload                   jsonb not null
  status                    text not null
  result                    jsonb not null default '{}'
  attempt_count             integer not null default 0
  next_attempt_at           timestamptz null
  expires_at                timestamptz null
  created_at                timestamptz not null
  updated_at                timestamptz not null
```

`profile_cursor` 是 channel-gateway 分配给 extension-facing SSE 的单调 cursor。

状态建议：

```text
RECEIVED
EMITTED
ACKED
SENT
ACCEPTED
UNSUPPORTED
FAILED_RETRYABLE
FAILED_TERMINAL
EXPIRED
```

transient frame TTL：

1. `TYPING_* / DRAFT_*` 默认保留 15 分钟。
2. 过期未消费的 transient frame 置为 `EXPIRED`，不再重放。
3. `FINAL_DELIVERY` 不按短 TTL 删除，必须保留 delivery 审计与重试状态。

## 6. channel-gateway -> extension SSE

extension 面向 channel-gateway 建立 profile 级长连接：

```http
GET /extension/channel/outbound-frames/stream?channelProfileId={channelProfileId}
Accept: text/event-stream
Authorization: Bearer {extension-token}
X-Lynxus-Extension-Registration-Id: {registrationId}
X-Lynxus-Extension-Descriptor-Type: CHANNEL_PROVIDER
X-Lynxus-Extension-Descriptor-Id: {providerType}
Last-Event-ID: {profileCursor}
```

SSE event：

```text
id: 105
event: channel-outbound-frame
data: {"protocol":"lynxus.channel-outbound-frame.v1", "frameId":"...", "...":"..."}
```

这里 `id` 是 `profileCursor`，不是 `frameId`。

连接规则：

1. extension client 应在 channel profile ACTIVE 后立即连接。
2. extension client 不根据 inbound、playbook、session 或 turn 判断何时连接。
3. 同一个 `channelProfileId + registrationId + providerType` 同一时间只允许一个 active extension stream consumer。
4. 如果第二个 consumer 连接：
   - 推荐返回 `409 CONFLICT`。
   - 或者显式踢掉旧连接，但必须记录 structured log。
5. SSE heartbeat 使用 comment：

```text
: heartbeat
```

6. heartbeat 间隔建议 15-30 秒。
7. extension client 必须用 `Last-Event-ID` 断线重连。

## 7. extension result/ack

SSE 不能承载双向确认，因此 extension 必须调用 result endpoint：

```http
POST /extension/channel/outbound-frames/{frameId}/result
Authorization: Bearer {extension-token}
Idempotency-Key: {frameId}
Content-Type: application/json
```

request body：

```json
{
  "channelProfileId": "channel-profile-1",
  "providerType": "enterprise.acme.im",
  "status": "SENT",
  "retryable": false,
  "externalMessageId": "msg-remote-1",
  "metadata": {
    "providerStatus": "ok"
  }
}
```

status：

```text
ACKED
SENT
ACCEPTED
UNSUPPORTED
NO_OP
FAILED
```

result 语义：

1. `ACKED` 只表示 extension 收到并持久化了 frame，不表示已经发送到外部平台。
2. `SENT` 表示外部平台已确认发送。
3. `ACCEPTED` 表示外部平台异步受理。
4. `UNSUPPORTED` 表示 provider 不支持该 frame kind。
5. `NO_OP` 表示重复或当前状态下无需处理。
6. `FAILED + retryable=true` 触发 channel-gateway 重试。
7. `FAILED + retryable=false` 进入 terminal failure。

ack 要求：

1. `FINAL_DELIVERY` 必须回传 result。
2. `DRAFT_COMPLETE` 如果创建或更新了外部 draft message，必须回传 result，并可返回 `externalMessageId`。
3. `DRAFT_UPDATE / TYPING_* / DRAFT_DISCARD` 可以只回 `ACKED`；失败不应阻断 final delivery。
4. result endpoint 必须对同一个 `frameId` 幂等。

## 8. Capability 与 manifest 改造

channel provider descriptor 的 outbound 能力需要从散落布尔值收敛为明确对象：

```json
{
  "outbound": {
    "mode": "FRAME_STREAM",
    "supportsTyping": true,
    "supportsDraftUpdate": true,
    "supportsFinalDelivery": true,
    "supportsDeliveryResult": true
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
3. 如果 `supportsDraftUpdate=false`，gateway 不向 extension 下发 `DRAFT_UPDATE / DRAFT_COMPLETE / DRAFT_DISCARD`，但仍可下发 `TYPING_* / FINAL_DELIVERY`。
4. 如果 `supportsTyping=false`，gateway 不下发 `TYPING_*`。

## 9. Backpressure 与合并

不能把每个模型 token 无限制推给 extension。

API 发布侧：

1. 对 `DRAFT_UPDATE` 做 profile/session/turn/block 维度的短窗口合并。
2. 默认合并窗口 100ms。
3. 合并后仍保留 source ordering。
4. customer-visible 校验仍在 API 边界完成，不能把 INTERNAL/DEVELOPER frame 投到 channel outbound frame。

channel-gateway 下发侧：

1. 如果 extension SSE consumer 落后太多，transient frame 可以过期丢弃。
2. `FINAL_DELIVERY` 不允许因 backpressure 丢弃。
3. 同一个 `DRAFT_UPDATE` frame 重试时必须使用同一个 `frameId`。
4. 同一个 final delivery 的重试必须使用同一个 `frameId` 和 `Idempotency-Key`。

## 10. Error 与恢复

API -> channel-gateway 断线：

1. channel-gateway 用已持久化的 upstream cursor 重连。
2. API 根据 `Last-Event-ID` replay。
3. replay 缺失 transient frame 时允许跳过。
4. replay 缺失 final delivery 时必须从 durable session message/outbox 补发。

channel-gateway -> extension 断线：

1. extension 用 `Last-Event-ID` 重连。
2. channel-gateway replay `profileCursor` 之后未过期 frames。
3. transient 过期后不补。
4. final delivery 未 terminal 前必须继续可 replay 或重试。

extension result 丢失：

1. extension 重试 `POST result`，`Idempotency-Key=frameId`。
2. channel-gateway result endpoint 幂等。
3. final delivery 在超时未收到 terminal result 时按 retry policy 重新 emit 同一 frame。

## 11. 安全边界

1. API internal SSE 只接受 internal bearer token。
2. extension-facing SSE/result endpoint 必须校验：
   - extension token
   - registrationId
   - descriptor type/id
   - providerType 与 channel profile 匹配
   - channel profile ACTIVE
3. extension stream 只能订阅自己 providerType/registrationId 暴露的 profile。
4. frame payload 不包含 secret、account credential、raw provider token。
5. `externalSecretRef` 不进入 extension-facing frame；extension 侧凭证解析仍走既有 Integration Account / Credential 机制或由 gateway-native adapter 内部处理。
6. customer-visible frame 只允许客户可见文本和 canonical message block。

## 12. 实施阶段

### Phase 1: 协议与契约

1. 在 `packages/contracts-jvm` 增加 `ChannelOutboundFrame`、`ChannelOutboundFrameKind`、`ChannelOutboundFrameResult`。
2. 在 `packages/contracts` 增加对应 TypeScript contract。
3. 在 `packages/contracts/openapi/control-plane.yaml` 增加 API internal SSE endpoint。
4. 在 `packages/extension-protocol` 增加 extension-facing stream/result OpenAPI 与 JSON schema。
5. 更新 extension SDK JVM/Python 的协议常量和 contract tests。

验收：

1. contract tests 覆盖 frame schema、result schema、非法 kind/payload 组合。
2. `frameId`、`sourceCursor`、`profileCursor` 的语义在 OpenAPI description 中明确。

### Phase 2: API frame publisher

1. 新增 `ChannelOutboundFramePublisher`。
2. `DefaultSessionChannelActivityRelay` 改为发布 activity frame。
3. `SessionChannelOutboundRelay` 改为发布 `FINAL_DELIVERY` frame。
4. 删除 API 到 channel-gateway 的逐 frame `sendOutboundActivity` 调用。
5. 删除 API 到 channel-gateway 的同步 final `deliverOutbound` 调用。
6. 实现 per-profile SSE emitter、Redis broadcast、replay buffer。

验收：

1. `REPLY_BLOCK_DELTA` 不再产生 `POST /internal/channel-outbound/activities`。
2. `REPLY_BLOCK_COMPLETED` 产生 `DRAFT_COMPLETE` 和最终 durable message 对应的 `FINAL_DELIVERY`。
3. API 双实例下，gateway 连接任一实例都能收到 profile frame。

### Phase 3: channel-gateway upstream subscriber

1. 为 ACTIVE profile 建立 API internal SSE subscriber。
2. 增加 profile ownership lock。
3. 持久化 upstream frame 和 cursor。
4. 对 duplicate `frameId` 幂等处理。
5. 将持久化后的 frame 发布给 extension-facing SSE replay store。

验收：

1. 多个 gateway 实例只有一个订阅同一 profile。
2. owner 切换后使用 cursor 续订，不重复 final delivery。
3. API stream 断线恢复后不丢 final delivery。

### Phase 4: extension-facing SSE/result

1. 新增 `/extension/channel/outbound-frames/stream`。
2. 新增 `/extension/channel/outbound-frames/{frameId}/result`。
3. 实现 profile consumer lease，避免多个 extension consumer 重复消费同一 profile。
4. 实现 `Last-Event-ID` replay。
5. 实现 result 幂等和 final delivery retry。

验收：

1. extension 断线后可用 `Last-Event-ID` 续收。
2. 同 profile 第二个 extension stream 被拒绝或明确替换旧连接。
3. final delivery 未 result 时会按 retry policy 重新 emit 同一 `frameId`。

### Phase 5: provider adapter 重构

1. remote extension provider 改为 frame stream client。
2. gateway-native provider 改为消费同一 `ChannelOutboundFrame` interface。
3. Feishu native provider 先实现 `FINAL_DELIVERY`；typing/draft 是否支持由 capability 明确声明。
4. 删除 `sendActivity` adapter 接口。
5. 删除 remote `sendOutbound` 同步调用路径。

验收：

1. TEXT final message 可通过 frame stream 完成发送并回写 result。
2. 不支持 draft 的 provider 不收到 draft frames。
3. 不支持 typing 的 provider 不收到 typing frames。

### Phase 6: 观测和故障注入

新增 metrics：

```text
lynxus.channel_outbound.api_stream.connected
lynxus.channel_outbound.api_stream.reconnect
lynxus.channel_outbound.extension_stream.connected
lynxus.channel_outbound.extension_stream.reconnect
lynxus.channel_outbound.frame.emitted
lynxus.channel_outbound.frame.result
lynxus.channel_outbound.frame.retry
lynxus.channel_outbound.frame.expired
```

新增 structured logs 字段：

```text
channelProfileId
providerType
frameId
sourceCursor
profileCursor
kind
sessionId
turnId
turnExecutionId
externalConversationId
resultStatus
```

故障注入：

1. API SSE 中断。
2. channel-gateway owner 切换。
3. extension SSE 中断。
4. extension result 丢失。
5. high-frequency draft delta。
6. replay buffer miss。

## 13. 最终验收标准

1. API 到 channel-gateway 不再对每个 transient frame 发 HTTP POST。
2. channel-gateway 到 extension 不再通过 `sendActivity` / `sendOutbound` 同步 POST 投递 outbound。
3. ACTIVE channel profile 会稳定维护 outbound frame stream。
4. playbook、人工回复、session replay、普通 agent reply 产生的 outbound 都能进入同一 frame stream。
5. extension 断线重连后能继续按 profile cursor 消费。
6. transient frame 可过期丢弃，但 final delivery 不能丢。
7. 多 API / 多 channel-gateway 实例下不会重复消费同一 profile。
8. 多 extension 实例下不会因为多个 SSE consumer 重复发送外部消息。
9. frame/result 协议有 contract tests。
10. Feishu native provider 和至少一个 remote extension provider 有端到端测试覆盖。
