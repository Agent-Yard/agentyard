# 统一 Output Message 协议并一次性移除 `finalReply`

## Summary
一次性完成 runtime 会话输出协议重构：LLM 不再输出单一 `message: string`，而是输出结构化 `outputMessages[]`；runtime 负责把增量消息转成 workflow 级累计 `WorkflowOutputMessage`，并在遇到 `EXTERNAL_INTERACTION` 时隐式进入 `EXTERNAL_SYSTEM` 暂停。控制面 API 改为按 `outputMessages` 做幂等投影，并把消息、interaction task、interaction event、workflow/session 更新放进同一个事务。`finalReply` 从 runtime contracts、API DTO/OpenAPI、数据库、前端全部删除；`summary` 保留为 workflow 观测摘要字符串。

## Key Changes

### 1. LLM 输出协议与 runtime 状态机
- 将 `StructuredAgentDecision` 从 `message` 升级为 `outputMessages`，每项只允许模型输出 `payloadType + payload`；本次支持 `TEXT` 和 `EXTERNAL_INTERACTION`。
- `TEXT` payload 固定为 `{ text: string }`；`EXTERNAL_INTERACTION` payload 固定为 `{ spec: { interactionType, title, instruction, provider, providerReference, launchUrl, returnPath, expiresAt, primaryActionLabel, secondaryActions, displayHints } }`。
- 保留现有 `decisionType`、`routeDecision`、`humanRequest` 语义；external interaction 不新增单独 `pauseRequest`，而是由 `outputMessages` 中出现 `EXTERNAL_INTERACTION` 隐式触发暂停。
- runtime 新增统一的“发消息”流程：
  - 模型输出的是本次决策的增量 `outputMessages`。
  - runtime 将其追加到 `state.output_messages`，形成 workflow 级累计 append-only 列表。
  - runtime 生成 `messageKey`、`createdAt`，模型不能提供这两个字段。
- `messageKey` 采用确定性规则生成：`<nodeKey>:<turnIndex>:<ordinal>`；同一次 agent 决策中第几个输出消息就固定对应第几个 key。
- 允许一次决策输出多条消息；最多允许一条 `EXTERNAL_INTERACTION`，且必须是该次决策的最后一条。前面可以有 `TEXT` 解释消息。
- 当最后一条输出是 `EXTERNAL_INTERACTION` 时，runtime：
  - 解析其 `spec`
  - 先把消息写入累计 `state.output_messages`
  - 根据 `routeDecision` 解析 resume 后继续节点
  - 写入 `resume_task / pause_reason / checkpoint.resumeContext(source=EXTERNAL_SYSTEM, interactionType=...)`
  - 将 workflow 置为 `WAITING_RESUME`
- 非 interaction 的 FINAL 决策不再通过 `finalReply` 驱动会话；conversation 真相源只来自 `outputMessages`。
- `summary` 在 runtime 内继续保留并统一派生：
  - 若本次有输出消息，则取最后一条消息的字符串摘要
  - `TEXT` 摘要为 `payload.text`
  - `EXTERNAL_INTERACTION` 摘要为 `title/instruction/status` 组合
  - 若没有输出消息，则回退到已有流程摘要逻辑

### 2. Shared contracts / API / Web 类型
- 从 shared contracts、Python model、JVM contracts、OpenAPI、TypeScript contracts 中删除 `WorkflowResult.finalReply` / `WorkflowInstance.finalReply`。
- 保留 `WorkflowResult.summary` 和 `WorkflowInstance.summary`。
- `WorkflowOutputMessage` 继续作为 runtime -> API 的共享结构，字段为 `messageKey, payloadType, payload, createdAt`。
- 前端所有 runtime/workflow 页面改为：
  - 会话展示仅依赖 `conversation.messages`
  - workflow 详情和列表不再展示“最终回复”
  - 继续展示 `summary`
- `ConversationMessage.content` 保留，但明确为 payload 派生的字符串摘要，不再代表模型原始输出字段。

### 3. 控制面投影与事务边界
- 将 `RuntimeService.reconcileWorkflowProjection(...)` 改成纯规划逻辑，返回 `ProjectionPlan`，不直接写库。
- `ProjectionPlan` 至少包含：
  - 更新后的 `task/workflow/session`
  - 需要 upsert 的 conversation messages
  - 需要 upsert 的 external interaction tasks
  - 需要 upsert 的 external interaction events
  - 可选 `resumeIntervention`
- repository 新增一个单事务的 `persistProjection(plan)`，JDBC 实现一次性落库：
  - task
  - workflow
  - session
  - conversation messages
  - external interaction task
  - external interaction event
  - intervention
- service 层不再单独调用 `saveExternalInteractionTask()` / `saveExternalInteractionEvent()` 后再调 `persistProjection()`；所有投影副作用必须进入同一事务。

### 4. 幂等键与持久化规则
- workflow emitted conversation message 的业务主键定义为 `(workflow_instance_id, message_key)`；用户消息仍按随机 `id`。
- workflow emitted message 的 `id` 改为确定性生成，基于 `(workflowId, messageKey)` 计算；不再使用 `nextId("msg")`。
- JDBC upsert 分两条路径：
  - `messageKey == null`：`ON CONFLICT (id)`
  - `messageKey != null`：`ON CONFLICT (workflow_instance_id, message_key)`
- `external_interaction_task` 增加 `source_message_key` 字段，并加唯一索引 `(workflow_instance_id, source_message_key)`。
- external interaction task 的 `id`、关联 `message_id`、created event 的 `id` 都改为基于 `(workflowId, messageKey)` 的确定性生成，避免重试/并发时出现“相同业务对象、不同随机 ID”。
- interaction created event 的 dedupe key 固定为 `create:<workflowId>:<messageKey>`；repository upsert 以业务唯一性为准，不依赖随机事件 id。
- `withInteractionCheckpoint(...)` 改为消费 plan 中已确定的 interaction task id，不在 service 内部临时随机生成。

### 5. 数据库与迁移
- 新建 migration：
  - 删除 `workflow_instance.final_reply`
  - 为 `external_interaction_task` 增加 `source_message_key`
  - 增加 `uk_external_interaction_task_workflow_message (workflow_instance_id, source_message_key)`
- 保留现有 `conversation_message.message_key` 和唯一索引 `(workflow_instance_id, message_key)`。
- 不做兼容逻辑，不保留旧列读写分支；所有读写路径一次性切换到新协议。

## Test Plan
- runtime Python tests
  - `FINAL + TEXT`：生成一条 `outputMessages(TEXT)`，workflow `COMPLETED`，不再返回 `finalReply`
  - `FINAL + TEXT + EXTERNAL_INTERACTION`：生成两条累计消息，workflow `WAITING_RESUME`，checkpoint 的 `resumeContext.source=EXTERNAL_SYSTEM`
  - 非法输出校验：`EXTERNAL_INTERACTION` 不是最后一条、一次决策出现多条 interaction、payload 不符合 schema 时直接失败
  - `messageKey` 生成稳定，同一状态重跑不会变化
- API/service tests
  - refresh/polling 多次消费同一 `outputMessages` 不会重复插入 conversation message
  - 同一 `EXTERNAL_INTERACTION` 多次投影只存在一个 task、一条 created event
  - interaction return/callback 后仅更新 projection 部分，不改写 `spec`
  - workflow/session/message/task/event 任一步失败时整体回滚，不留下半投影状态
- JDBC repository tests
  - workflow emitted message 走 `(workflow_instance_id, message_key)` 幂等 upsert
  - user message 仍走 `id` upsert
  - external interaction task 走 `(workflow_instance_id, source_message_key)` 幂等 upsert
- Web tests
  - runtime conversation page 正确渲染 `TEXT` 和新的 interaction payload 结构
  - workflow page / runtime detail 不再引用 `finalReply`

## Assumptions / Defaults
- 本次仅定义两种 payload type：`TEXT`、`EXTERNAL_INTERACTION`；协议形状允许后续继续扩展，但本次不新增第三种类型。
- external interaction 由消息类型隐式触发暂停，不额外增加 `pauseRequest` 字段。
- `summary` 继续作为 workflow 观测摘要保留；会话对用户可见内容以 `conversation_message.payload` 为真相源。
- `outputMessages` 是单次 agent 决策的增量输入、workflow 级累计状态输出；累计 append-only 存在 runtime state 中。
- 不考虑向后兼容、旧数据兼容或双写过渡；按一次性重构实施。
