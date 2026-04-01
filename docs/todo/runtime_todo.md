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

## 2. 已完成：草稿默认模型语义收敛

当前状态：

- Assistant 草稿默认模型已显式收敛为 `defaultModelResourceId`，主链路不再自动挑选第一个可用 `LLM_MODEL`
- 发布前会强制校验默认模型是否已配置；未配置时直接阻断发布
- Runtime 只在“无 `currentRelease` 的草稿助手且未配置默认模型”时阻断运行；已有发布版时继续使用发布快照里的冻结模型绑定
- `AssistantRelease` 已新增 `defaultModelBinding`，明确保存发布时冻结的模型资源与版本信息
- `WorkflowResult / WorkflowInstance` 已新增 `modelHits`，agent-runtime 会在真实发起模型调用前记录命中快照
- 控制台已能分别展示：
  - 草稿默认模型
  - 当前发布冻结模型
  - 最近一次 / 当前 workflow 的实际模型命中

说明：

1. 本轮按项目规则不对旧 `providerResourceId` 数据或旧 release snapshot 做兼容与回填
2. 旧库若仍保留历史快照，需要通过重建本地/开发数据来获得新语义

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
