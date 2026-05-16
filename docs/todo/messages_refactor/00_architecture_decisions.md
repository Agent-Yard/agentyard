# Messages Refactor Architecture Decisions

## 1. Core Model

采用 `turn -> messages -> blocks` 三层模型：

- `turn`：一次后端处理批次，是幂等、恢复、审计、agent execution 和 outbound 归属边界
- `messages[]`：一次 turn 内的多条用户可见消息，保留独立顺序、role、sender、metadata、外部消息 ID 和真实发生时间
- `blocks[]`：单条 message 内的内容部件，类型保持 `TEXT / IMAGE / RICH_TEXT / CARD`

不把多条用户消息压成一条 message 的多个 block。多 block 只表示“一条消息包含多种内容”，例如文字 + 图片、文字 + 附件卡片。

## 2. Entry Scope Is Part Of Session Identity

active session 唯一身份必须带入口 scope：

- Web/console：`WEB + customerId + assistantId`
- Channel：`CHANNEL + channelProfileId + externalConversationId + customerId + assistantId`

`externalConversationId` 必须进入 channel active identity，避免同一用户在同一 channel profile 的不同私聊/群聊中复用同一个 session，也避免 outbound 目标不明确。

active identity 的唯一性必须由 `session_runtime_session` 行本身承载。API 在启动 workflow 前创建或复用该行；worker `run()` 不再插入首个 session row，只能更新已有 session projection。否则并发首条消息会在 session row 落库前绕过 DB unique index，Redis lock 也会被误用成正确性边界。

API create-or-reuse session 的 authoritative boundary 是 DB partial unique index：

- Web active identity：`entry_scope = WEB` 且 `(customerId, assistantId)` 在非 `ENDED` session 中唯一
- Channel active identity：`entry_scope = CHANNEL` 且 `(channelProfileId, externalConversationId, customerId, assistantId)` 在非 `ENDED` session 中唯一
- Redis identity/session lock 只能减少 in-flight 竞争，不能代替 DB unique index
- `sessionId` 同时是 `session_runtime_session.id` 和 Temporal workflow id
- workflow start 是 DB commit 之后的幂等副作用；API 重试必须能在发现 session row 已存在但 workflow 未启动时重新 ensure-start

## 3. Web And Trusted Inbound Use Different Role Boundaries

Web/console public API 不能允许客户端声明任意 `role/sender`：

- Web turn request 只允许用户输入字段，服务端强制写 `role = USER`、`sender.senderType = CUSTOMER`
- Channel/internal import turn request 才允许传入 `USER / ASSISTANT / HUMAN_OPERATOR / SYSTEM`
- 所有外部输入或导入消息统一写 `producer_type = EXTERNAL`
- agent 回复、平台内人工回复、平台系统消息统一写 `producer_type = PLATFORM`

这样才能阻止普通客户端伪造 assistant/system transcript，也能保证 channel outbound 不重放导入历史消息。

非 channel transcript import 使用单独的 trusted import turn API，不复用 channel inbound turn API。import 是消息来源和写入口，不是新的 session entry scope；导入目标必须显式指向已有 session，或声明 Web/Channel create-or-reuse identity。导入的 assistant/operator/system 历史消息必须可进入 transcript replay，但 `producer_type = EXTERNAL`，不得生成 channel final delivery。

## 4. Turn Idempotency Is DB-Backed

`session_runtime_turn` 是 turn 幂等与 ID 恢复的权威状态。Redis 只能做短期 in-flight/cache 优化，不能作为唯一恢复来源。

幂等边界覆盖：

1. create-or-reuse session
2. idempotent workflow ensure-start for the selected session
3. `turnId/messageId` 分配
4. message-level duplicate detection
5. session messages append
6. Temporal update accepted stage

一旦创建 `session_runtime_turn`，同一个 `(sessionId, turnDedupKey)` 永远不能重新分配 `turnId/messageId`。

## 5. Temporal Update Must Be Idempotent Too

API 重试不能只依赖 DB 状态。Temporal update 也必须具备幂等边界：

- `submitUserTurn` 使用稳定 update id，优先使用 `turnId`
- workflow 内部记录已 accepted/processed 的 `turnId`
- 同一 `turnId` 重放直接返回已知 accepted result，不重复触发 agent execution
- API 等待 update accepted stage，不等待 completed result
- `BUSY / REJECTED` 语义必须发生在 Temporal accepted stage 之前；accepted 之后不再作为 workflow 普通业务返回值出现

这解决“API 已提交 update 但还没更新 `session_runtime_turn.status` 时进程崩溃”的重复提交风险。

Rejection boundary:

- API 能判定的拒绝必须在创建或推进 turn 前完成，例如空消息、参数错误、target session mismatch、active identity mismatch、`agentTurnActive`、`draining`、`ENDED`
- 这些 API preflight rejection 不创建 `session_runtime_turn`，也不 append message
- workflow-only race guard 使用 Temporal `@UpdateValidatorMethod`；如果拒绝，update 不进入 accepted stage，API 不得把 turn 标记为 `WORKFLOW_ACCEPTED`
- 一旦 update 进入 accepted stage，workflow 只处理 accepted turn：重复 `turnId` 返回 accepted/idempotent result，模型失败、安全拦截、工具失败都属于 accepted turn 的处理结果，而不是 `BUSY / REJECTED`

## 6. Workflow Receives Persisted Message Delta

外部输入消息由 API 先落库，workflow 不再 append 外部输入消息。

`UserTurn` 入参必须携带 API 已接受且已落库的 `SessionMessage` delta，而不是只携带未落库的轻量 input。原因：

- agent-runtime 需要 `sequence/turnIndex/producerType/externalMessageId/occurredAt`
- workflow 不应重新分配 message id 或 sequence
- bootstrap rebuild 必须从权威 session message projection 重建

## 7. One Session Turn Can Contain Multiple Model Executions

一次 accepted turn 只对应一个 `turnId`，但 workflow 内部 owner switch 可能产生多个 agent-runtime model execution。

规则：

- 同一个 `turnId` 下可以有多个 `turnExecutionId`
- `turnExecutionId` 使用稳定序号，例如 `{turnId}:exec-1`、`{turnId}:exec-2`
- agent reply/security block/failed reply 都挂在触发它的 `turnId` 下
- 不为 agent reply 单独创建新 turn

这保留当前 owner switch 能力，同时不破坏 turn 作为用户批次和审计边界的语义。

## 8. Message Ordering Comes From Persistence

所有消息 append 收敛到 persistence 统一入口，例如 `appendSessionMessages(sessionId, turnId, messages)`。

统一入口必须：

- 在单个 DB 事务内锁定 `session_runtime_session` 当前行
- 读取并递增 `next_message_sequence`
- 为批量消息分配连续 `sequence`
- 按 turn 内已有消息数继续分配 `turnIndex`
- 插入 `session_runtime_message`
- 更新 `session_runtime_turn.message_ids`

workflow 内存态不得使用 `messages.size() + 1` 推导最终 `sequence`。append activity 应返回落库后的 `SessionMessage`，workflow 再更新内存态。

## 9. Channel Inbound Audit Must Preserve Duplicates

Channel inbound 需要 turn/message 两层审计：

- `channel_inbound_turn`：批次级幂等和 dispatch 状态
- `channel_inbound_turn_message`：本批次内消息状态、排障字段和 request 顺序
- 独立 message dedupe 状态：跨 turn 的 `(channelProfileId, externalConversationId, externalMessageId)` 去重结果

不能让 `channel_inbound_turn_message` 的唯一约束阻止重复消息审计行落库。重复消息应可记录为 `DUPLICATE`，但不应再次 dispatch 到 session-runtime。

message-class channel inbound turn 使用同步 dispatch 到 session-runtime。gateway 对非重复消息的 provider response 必须反映 session-runtime accepted/rejected/failed 结果，不引入 queued eventual acceptance 状态。dispatch 失败后通过 `channel_inbound_turn.status = FAILED` 和 message `RECEIVED` 状态恢复，重试只 dispatch 未完成消息。

## 10. Agent Runtime Uses Transcript Replay Plus Delta

agent-runtime 不再接收 recent session history window，也不再依赖 `triggerMessageId`。

输入改为：

- `messages`：本 turn 要追加到 provider transcript 的 session message delta
- `contextEntries`：session event、sharedState、activePlaybook 等运行上下文发生变化时的 delta
- `transcriptBootstrap`：当 committed transcript 缺失、过期、owner context 切换或需要重建时启用

`sharedState` 字段仍作为 runtime tool/policy 的当前权威快照，但是否渲染进 provider transcript 只由 `contextEntries` 决定。

`contextEntries` 由 workflow 生成，不能由 agent-runtime 反向读取 session-runtime 推断。`shared_state_revision` 是 shared state patch/snapshot 的单调 revision 来源。删除 `recentEvents` 后，human resume、external callback、playbook state、owner switch 和 system wakeup 等非 message 上下文必须通过 `contextEntries` 进入 provider transcript。

agent-runtime 的 context tools 也必须跟随这个边界：删除依赖 `recentEvents` 的 `list_recent_events` 工具，或改成只读取当前 request 中的 `contextEntries`。工具不得重新引入 recent event window，也不得反向查询 session-runtime。

## 11. Bootstrap Reset Needs Durable Reset Marker

`transcriptBootstrap = true` 时，agent-runtime 必须通过 transcript store 的幂等 reset API 清理当前 owner context 下已提交 transcript。

需要在 transcript store 持久化 reset marker，表达：

- 哪个 `turnExecutionId`
- 哪个 `providerType`
- 哪个 `sessionId / ownerAgentId / ownershipEpoch`
- 哪个 `executionAttemptId` 首次完成 reset
- reset status、reset 完成时间、清理的 committed entry 数量

同一 `turnExecutionId/providerType` 重试时不得重复删除已经重建后的 committed transcript。已经 `SUCCEEDED` 的 execution 不得再 reset。即使 reset marker 已完成，bootstrap reset API 仍需幂等清理 transcript cache，避免读取 reset 前的 stale cache。

## 12. Channel Outbound Only Delivers Platform Messages

channel outbound final replay 只选择：

- `producer_type = PLATFORM`
- `role in (ASSISTANT, HUMAN_OPERATOR, SYSTEM)`
- status 是最终可投递状态
- blocks 非空

外部进入或系统导入的 transcript 消息，即使 role 是 `ASSISTANT/HUMAN_OPERATOR/SYSTEM`，也不能生成 channel `FINAL_DELIVERY`。

## 13. Stream And Web Draft IDs Are Semantic

Stream payload 对外也使用 `replyMessageId`，不保留 `messageId` 作为 Web-facing alias。原因是 turn 内既有用户输入消息，也有平台回复消息，`messageId` 在不同上下文里容易被误读成 trigger/input message id。

命名边界：

- `SessionMessage.messageId`：持久化 transcript message id
- `acceptedMessageAllocations[].messageId`：本次 accepted user/input message 的持久化 id
- `replyMessageId`：agent/operator/system 平台回复消息 id，所有 turn stream frames 使用它绑定同一个回复 draft
- `clientMessageId`：Web 本地草稿 id，只用于前端提交后把 draft 替换成已落库 message

Web draft reconciliation 必须由服务端契约支撑：

- request 每条 message 可带 `clientMessageId`
- response 返回 `acceptedMessageAllocations[]`，显式给出 `requestIndex/clientMessageId/messageId/turnIndex`
- Web 不能只靠 `acceptedMessageIds[]` 猜测 draft 顺序
- visible chat 按 message 级别渲染；`turnId` 只用于诊断、stream grouping 和关联同批处理

## 14. Direct Refactor, No Compatibility Layer

本项目规则要求不优先兼容旧数据和旧接口。本专项按目标架构直接重构：

- 不保留旧 `/api/session-runtime/messages` 作为并行写入口
- 不维护单消息 workflow update 与新 turn update 双路径
- 文档、OpenAPI、contracts、SDK、测试全部改向新模型
