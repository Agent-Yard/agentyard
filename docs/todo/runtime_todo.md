# Runtime TODO

这份文档记录当前运行链路仍待补齐的工作项。
和 `docs/develop_record/` 中的重构留档不同，这里只保留“当前仍有效”的待办。

## 1. 已完成：API 启动/恢复链路改为异步观测

当前状态：

- `POST /api/tasks`
- `POST /api/runtime/sessions/{sessionId}/messages`
- `PATCH /api/workflows/{workflowId}/human-action`

这些链路现在都只负责提交命令并立即返回已受理投影，不再同步等待 workflow 暴露首个业务结果。

当前实现细节：

- gateway 暴露 `start(...)`、`submitHumanAction(...)` 和 `currentResult()`
- API 先落 `session/task/workflow` 初始投影，再提交 workflow 或 signal
- 前端主入口统一依赖 workflow 列表/详情轮询收口
- `WAITING_HUMAN / COMPLETED / FAILED / CANCELLED` 全部通过运行态观测自然传播

本次交付：

- `sendMessage` / `launchTask` 立即返回受理后的运行标识和占位消息
- 人工恢复链路改为立即返回“恢复处理中”的 workflow 投影
- “workflow did not expose a result before timeout” 已不再是 API 层产品语义

剩余相关工作：

1. 视需要在轮询之上再补 SSE / WebSocket 实时推送
2. 为异步观测链路补更细的失败码和恢复态可观测信息（见 §3）
3. 视流量与体验需求决定是否把全局轮询拆成按 workflow 增量订阅

## 2. 统一 demo seed、草稿默认模型和发布快照语义

现状：

- catalog seed 的默认模型由 `CatalogService.defaultLlmResourceId()` 动态决定
- 规则仍然是：
  - 存在 `LYNXUS_OPENAI_COMPATIBLE_BASE_URL` 或 `OPENAI_COMPATIBLE_API_KEY` 时优先选 `resource-llm-compatible`
  - 否则若存在 `OPENAI_API_KEY`，选 `resource-llm-openai`
  - 否则回退到 `resource-llm-compatible`
- assistant 发布后，runtime 实际命中的仍是 release snapshot 中冻结的资源锚点
- runtime demo session seed 已从 API 主链移除，当前只保留 catalog seed 与 `demo.local` provider 演示闭环

当前缺口：

- “演示默认模型”仍然是隐式环境变量策略，不够可见
- 已有数据库中的旧 release snapshot 不会因代码默认值变化而自动回写
- UI 还不能清楚区分草稿默认模型、当前发布冻结模型和实际 workflow 命中模型

后续目标：

1. 增加显式的演示模式默认模型策略，而不是继续隐含依赖环境变量优先级
2. 为 demo seed 增加版本戳或迁移策略，必要时自动重建旧演示数据
3. 在控制台明确展示草稿模型、发布冻结模型和运行命中模型三层语义

## 3. 已完成：增强 workflow 失败可观测性

当前状态：

- `WorkflowResult` / `WorkflowInstance` 已新增 `latestFailure`
- `latestFailure` 已持久化到 `workflow_instance.latest_failure jsonb`
- Web 流程观测页和会话页都能直接展示 `category / code / rootCause / failedNode / failedResource / occurredAt`
- 错误转人工时同时保留：
  - `pauseReason`：表达“为什么当前在等人工”
  - `latestFailure`：表达“最近一次结构化失败诊断”

当前规则：

1. `FAILED` workflow 会保留结构化 failure snapshot
2. 因错误进入 `WAITING_HUMAN` 的 workflow 也会保留结构化 failure snapshot
3. 纯业务暂停不写 `latestFailure`：
   - `GRAPH_HUMAN_NODE`
   - `HUMAN_HANDOFF_REQUESTED`
4. workflow 后续恢复或完成后，当前投影不会主动清空 `latestFailure`；在完整审计表出现前，它承担最近一次排障线索沉淀

当前覆盖的失败分类：

- `TIMEOUT`
- `PROVIDER_FAILURE`
- `TOOL_FAILURE`
- `PARSING_FAILURE`
- `VALIDATION_FAILURE`
- `CONFIGURATION_FAILURE`
- `RUNTIME_FAILURE`
- `UNKNOWN`

## 4. 已完成：持久化 runtime 会话与运行观测投影

当前状态：

- catalog 已经落到 PostgreSQL
- `ConversationSession / ConversationMessage / TaskInstance / WorkflowInstance / HumanIntervention` 已落 PostgreSQL
- `RuntimeService` 已切换为 repository 驱动，数据库投影是运行态权威数据源
- API 启动时会读取数据库中的非终态 workflow，并主动向 Temporal 查询 `currentResult()` 做对账

本次交付：

- workflow 启动、等待人工、恢复、完成、失败都会增量落库
- 会话消息更新与人工处理记录会同步回写数据库
- 控制台刷新、API 重启后仍能稳定查询 runtime 业务视图

剩余相关工作：

1. 为失败原因补齐结构化错误码、root cause 和失败资源字段（见 §3）
2. 视需要从当前投影模型升级到更完整的事件日志 / 审计模型
3. 继续评估更细粒度的 runtime 事件流，而不只依赖当前主投影

## 5. 把 agent 结构化决策升级为共享契约

当前状态：

- `agent-runtime` 已经在内部使用结构化决策载荷
- `packages/contracts`、`packages/contracts-jvm` 和 OpenAPI 已补齐：
  - `DecisionType`
  - `StructuredAgentDecision`
  - `ToolRequest`
  - `HumanRequest`
  - `SessionStatePatch`
  - `SessionStatePatchOp`
  - `AgentTurnLog`
  - `AgentTurnState`
- `WorkflowResult` / `WorkflowInstance` 已增加 `agentTurnState`
- 前端 workflow 观测页已能查看 `phase / turnIndex / latestDecision / turnLogs`

后续目标：

1. 把当前“最新 turn state”继续升级为更完整的历史审计模型
2. 在失败观测中复用共享决策契约，统一失败码与决策上下文
3. 为跨服务 schema 演进补版本策略和更细的集成测试
