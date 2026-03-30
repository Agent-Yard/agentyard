# Runtime TODO

这份文档记录当前运行链路仍待补齐的工作项。
和 `docs/develop_record/` 中的重构留档不同，这里只保留“当前仍有效”的待办。

## 1. 去掉 API 同步等待首个业务结果

现状：

- `POST /api/tasks`
- `POST /api/runtime/sessions/{sessionId}/messages`

这两条链路当前都会通过 `AssistantRunWorkflowGateway.startAndAwaitFirstResult(...)` 同步等待 workflow 暴露第一个可返回结果。

当前实现细节：

- gateway 通过 `currentResult()` 轮询 Temporal workflow
- 当前固定超时为 30 秒
- 启动链路允许返回 `WAITING_HUMAN`
- 人工恢复链路 `submitHumanActionAndAwaitResult(...)` 仍同步等待恢复后的结果

当前缺口：

- LLM、Tool 或 MCP 节点稍慢时，API 仍可能先超时
- “workflow did not expose a result before timeout” 不是稳定的产品语义
- 前端已经有 workflow 详情页，但主入口仍依赖同步等待

后续目标：

1. `sendMessage` / `launchTask` 只负责启动 workflow 并立即返回运行标识
2. 前端统一改为轮询 workflow 详情，或升级为 SSE / WebSocket 订阅
3. `WAITING_HUMAN / COMPLETED / FAILED` 全部通过运行态观测链路自然传播

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

## 3. 增强 workflow 失败可观测性

现状：

- workflow 失败时已经尽量写回 `FAILED`
- `WorkflowResult` 已经能带回 `summary`、节点轨迹、`latestToolOutcome` 等信息
- 会话页和流程页已经能看到基础运行状态

当前缺口：

- 失败原因还不够结构化，root cause、失败节点和失败资源没有稳定单独字段
- 不同失败类型还没有统一错误码体系
- LLM / Tool / MCP / runtime 内部解析失败的展示口径还不够一致

后续目标：

1. 在 workflow 详情中直接展示 root cause、失败节点和失败资源
2. 区分 timeout、provider failure、tool failure、runtime parsing failure 等类型
3. 为关键失败路径补充稳定错误码和更可读的错误摘要

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

1. 去掉同步等待首结果，改为真正的异步观测链路（见 §1）
2. 为失败原因补齐结构化错误码、root cause 和失败资源字段（见 §3）
3. 视需要从当前投影模型升级到更完整的事件日志 / 审计模型

## 5. 把 agent 结构化决策升级为共享契约

现状：

- `agent-runtime` 已经在内部使用结构化决策载荷
- 当前核心形状包括：
  - `decisionType`
  - `message`
  - `routeDecision`
  - `skillReads`
  - `toolRequests`
  - `humanRequest`
- 决策类型当前为 `FINAL / TOOL_CALL / SKILL_READ / HUMAN_HANDOFF`

当前缺口：

- 这套结构目前主要停留在 Python runtime 内部
- `packages/contracts`、`packages/contracts-jvm` 和 OpenAPI 里还没有对应的共享契约
- 跨端调试、观测和后续演进仍容易出现字段漂移

后续目标：

1. 在 `packages/contracts`、`packages/contracts-jvm` 和 OpenAPI 中补齐共享结构化决策模型
2. 让 runtime、worker、API 和前端观测统一消费这套契约
3. 为结构化决策补齐单测、集成测试和回退策略
