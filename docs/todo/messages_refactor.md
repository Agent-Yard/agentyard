# Messages Refactor TODO

> 这份顶层文档只保留“多消息一次 turn”重构的执行入口。详细方案已拆到 [`messages_refactor/`](messages_refactor/) 专项目录。
> 目标不是兼容旧单消息链路，而是直接把 session-runtime、channel gateway、agent-runtime、web、contracts 收敛到 turn -> messages -> blocks 模型。

## 1. 目标

把当前“单条用户消息触发一次 workflow update”的链路，重构为：

- `turn`：一次后端处理批次，是幂等、审计、agent execution 归属和 outbound 投递的边界
- `messages[]`：一次 turn 内的多条用户可见消息，保留独立顺序、role、sender、外部消息 ID 和发生时间
- `blocks[]`：单条 message 内的内容部件，继续只使用 `TEXT / IMAGE / RICH_TEXT / CARD`

关键结果：

1. Web 首条消息、channel inbound、系统接手导入 transcript 都走 send-turn/create-or-reuse 语义
2. Channel 不再把不同 external conversation 复用到同一个 active session
3. 首条 turn 之前由 API 持久化 `session_runtime_session`，DB active identity 唯一约束成为并发控制边界
4. 外部进入或导入的 assistant/operator/system 历史消息不会被 channel outbound 再投递
5. agent-runtime 不再依赖 recent message/event window，而是依赖 committed provider transcript + 本轮 delta

## 2. 专项文档

- [00 Architecture Decisions](messages_refactor/00_architecture_decisions.md)：已经定下来的边界、状态机和阻断问题修正
- [01 Execution Plan](messages_refactor/01_execution_plan.md)：按依赖拆分的执行阶段、任务、验收和检查点
- [02 Contracts And Persistence](messages_refactor/02_contracts_and_persistence.md)：API、contracts、DB schema、统一 append 入口和幂等恢复
- [03 Workflow And Agent Runtime](messages_refactor/03_workflow_and_agent_runtime.md)：Temporal workflow、agent-runtime delta、transcript bootstrap reset
- [04 Channel And Web](messages_refactor/04_channel_and_web.md)：channel inbound turn、outbound 过滤、Web 客户端迁移
- [05 Verification Matrix](messages_refactor/05_verification_matrix.md)：测试矩阵、验证命令和完成口径

## 3. 执行顺序

1. 先落 contracts 与 schema 形状，尤其是 `SessionMessage` 新字段、`UserTurn` 数据形状、channel inbound turn 契约
2. 再落 API-owned create-or-reuse session store、persistence 统一 append 与 `session_runtime_turn` DB 幂等状态机
3. 再替换 API send-turn、idempotent workflow start 与 Temporal update idempotency
4. 再改 workflow 平台消息 append 和 no-external-input platform turn allocation
5. 再改 agent-runtime transcript delta
6. 再迁移 channel gateway 与 Web
7. 最后删除旧单消息路径、更新架构文档和全量验证

## 4. 当前必须遵守的决策

- 不保留旧 `/api/session-runtime/messages` 作为并行写路径；实施完成后只保留 turn 写入口
- Web/console 入口不能声明任意 role/sender，服务端强制写 `USER / CUSTOMER`
- 可信 channel/import 入口才允许导入 `USER / ASSISTANT / HUMAN_OPERATOR / SYSTEM`
- 非 channel transcript import 使用 trusted `/internal/session-runtime/import-turns`，import 是消息来源，不新增 `IMPORT` session entry scope
- API 是首个 `session_runtime_session` row 的创建方；worker `run()` 只能更新已有 session projection，不能作为 active identity 并发控制的创建方
- `session_runtime_session` 的 active identity 必须包含 `entry_scope`；channel identity 必须包含 `channelProfileId + externalConversationId + customerId + assistantId`
- `session_runtime_turn` 是 turn 幂等与 ID 恢复的权威状态；Redis 只能做短期 in-flight/cache 优化
- Temporal update 必须使用稳定 update id，优先使用 `turnId`
- API preflight 负责可由 DB/session projection 判定的拒绝；workflow-local race guard 使用 Temporal `@UpdateValidatorMethod`，不得在 update handler accepted 后返回业务 `BUSY / REJECTED`
- workflow 接收 API 已落库的 `SessionMessage` delta，不重新分配外部输入 message id，也不重复执行 external message 去重
- 所有 message append 必须走 persistence 统一入口，由 DB row lock 分配 `sequence` 与 `turnIndex`
- human operator、human resume、external callback、playbook completed、system wakeup 等无外部输入的平台动作必须先有 durable platform turn
- workflow 负责生成 `contextEntries`；`shared_state_revision` 是 shared state snapshot/patch 的单调 revision 来源
- transcript bootstrap reset 必须持久化 `transcript_bootstrap_reset` marker；同一 `turnExecutionId/providerType` 重试不能重复删除 committed transcript
- message-class channel inbound turn 使用同步 dispatch；provider response 反映 session-runtime accepted/rejected/duplicate/failed 结果
- channel outbound 只投递 `producer_type = PLATFORM` 且 role 为 `ASSISTANT / HUMAN_OPERATOR / SYSTEM` 的消息
- stream payload 对外统一使用 `replyMessageId` 表示平台回复消息；Web draft reconciliation 使用 `clientMessageId -> acceptedMessageAllocations.messageId`，回复 draft 使用 `turnId + replyMessageId`

## 5. 完成口径

完成后至少满足：

- Web 多条消息一次 turn 只触发一次 session turn，UI 仍按独立气泡展示
- Web 能用 `acceptedMessageAllocations` 将本地 user drafts 替换为已落库 `SessionMessage`，并用 `replyMessageId` 关联 assistant draft stream
- Channel 一批 inbound messages 只 dispatch 一次，重复 `externalMessageId` 不重复落 session message
- 同一 active identity 并发首条消息只能创建一个 active session
- API 崩溃在 session row 创建后、workflow start 前时，重试能复用同一 active session 并幂等确保 workflow 已启动
- 崩溃恢复能从 `session_runtime_turn` 复用同一组 `turnId/messageId`
- agent-runtime prompt 只追加本 turn 的 `messages + contextEntries` delta，历史上下文来自 transcript replay
- 删除 `recentEvents` 后，human resume、external callback、shared state、active playbook 和 owner switch 上下文仍通过 `contextEntries` 进入 prompt
- agent-runtime context tools 不再暴露依赖 `recentEvents` 的 `list_recent_events`；需要事件上下文时只能使用本次 request 的 `contextEntries`
- trusted import 的 assistant/operator/system 历史消息进入 transcript replay，但不会被 channel final delivery 投递
- channel final delivery 不会投递外部导入历史消息
- API/persistence/channel schema 改动通过 jOOQ generated-code verification，最终验证命令不使用根目录不存在的 `pnpm test`
- cleanup 搜索覆盖旧单消息 API、旧 channel message dispatch 类型和 `recent*` 字段，并排除 `docs/develop_record/`
