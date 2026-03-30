# 第一波改造计划：运行态异步化 + 结构化决策契约对齐

## Summary

第一波只做两件事，并且按一个闭环落地：

- `P0.2`：把 `sendMessage / launchTask / human-action` 从“同步等首结果”改成“立即返回已受理投影 + 前端轮询观测”
- `P1.1`：把 Python runtime 内部的结构化决策模型提升为共享契约，并在 workflow 观测页可见

默认实现策略已锁定：

- 观测方式用现有前端轮询，不做 SSE/WebSocket
- 人工恢复也改成立即返回，不再同步等待
- Web 端运行态/决策相关类型直接消费 `packages/contracts`
- 不做完整历史审计表，只暴露并持久化当前 `agentTurnState`

## Key Changes

### 1. Runtime 启动/恢复链路改为异步受理

- `AssistantRunWorkflowGateway` 改成非阻塞接口：
  - `startAndAwaitFirstResult(...)` 改为 `start(...)`
  - `submitHumanActionAndAwaitResult(...)` 改为 `submitHumanAction(...)`
  - `currentResult(...)` 保留，继续作为对账/轮询来源
- `RuntimeService` 的三条命令链路统一改成“先落初始投影，再触发 workflow”：
  - `sendMessage`：持久化用户消息、创建 `RUNNING` task/workflow、插入一条与 workflow 绑定的占位 assistant 消息，然后立即返回 session 快照
  - `launchTask`：持久化 `RUNNING` task/workflow 后立即返回 task 快照
  - `handleHumanAction`：保存 `PENDING` intervention，signal 成功后立即把 workflow/task/session 投影切到“恢复处理中”状态并返回 workflow 快照；后续由轮询对账成 `COMPLETED / FAILED / CANCELLED`
- 启动/恢复调用失败时只处理“命令未被受理”的失败：
  - workflow 未成功启动或 signal 未成功提交，则同步把投影标成失败并返回错误
  - 不再把“首结果未在 30 秒内返回”当成产品语义
- `createSession(openingMessage)` 保留异步语义：
  - 若传了 `openingMessage`，内部仍可复用 `sendMessage`，但返回的是已受理后的 session 投影，不等待 assistant 首结果

### 2. Runtime 投影与数据库补齐异步语义

- `WorkflowInstanceDto` 增加 `agentTurnState`
- `mergeWorkflowResult(...)`、`matchesWorkflowResult(...)`、`refreshSessionForWorkflow(...)` 全部纳入 `agentTurnState` 比较和回写
- `workflow_instance` 表新增 `agent_turn_state jsonb`
- `JdbcRuntimeRepository` / `InMemoryRuntimeRepository` 同步增加读写映射
- Session 消息刷新规则固定为：
  - 发送时先写入占位 assistant 消息
  - workflow 轮询到新结果后原地更新该消息内容
  - 若历史数据里缺占位消息，刷新时补一条，不丢最终回复

### 3. 结构化决策上升为共享契约

在 `apps/agent-runtime`、`packages/contracts`、`packages/contracts-jvm`、OpenAPI 四层统一新增并对齐以下模型：

- `DecisionType`
- `StructuredAgentDecision`
- `ToolRequest`
- `HumanRequest`
- `SessionStatePatch`
- `SessionStatePatchOp`
- `AgentTurnLog`
- `AgentTurnState`

统一字段以 Python 现有真实模型为准，不再另起一套简化命名。  
`WorkflowResult` / OpenAPI `WorkflowInstance` 新增：

- `agentTurnState.phase`
- `agentTurnState.turnIndex`
- `agentTurnState.latestDecision`
- `agentTurnState.turnLogs`

`apps/agent-runtime/app/main.py` 的变更原则：

- 继续保留现有内部决策流程
- 在 state 中显式保存 `latestDecision`
- `workflow_result_from_state(...)` 把 `agentTurnState` 一并输出
- 不做完整历史持久化，只输出当前 workflow 最新 turn state

### 4. Web 改成“命令立即返回 + 轮询收口”

- `apps/web/src/App.vue` 去掉“请求超时但其实 workflow 已启动”的补偿式控制流，改成命令成功后直接刷新一次并依赖轮询继续收口
- 保留现有 `3s` 全局轮询策略；只要存在 `RUNNING` workflow 就继续轮询
- `RuntimeConversationPage.vue` 更新文案与交互：
  - 发送后立即显示“已提交，后台执行中”
  - 不再把当前请求是否超时当成业务状态
- `WorkflowPage.vue` 新增结构化决策观测区：
  - 当前 phase / turnIndex
  - latestDecision 摘要
  - turnLogs 列表
- Web 的 runtime/decision 类型从 `packages/contracts` 导入；`apps/web/src/types.ts` 中对应本地重复定义删除或改为 re-export，仅保留未纳入共享包的 catalog 类型

### 5. 文档同步更新

- `docs/project_todos.md`：将第一波拆解项状态改成已完成/进行中后的真实状态
- `docs/todo/runtime_todo.md`：删除“同步等待首结果”现状描述，改成异步轮询现状；补上 `agentTurnState` 契约说明
- `README.md`：把 runtime 现状改成“启动即返回 + 前端轮询观测”，移除 30 秒等待语义描述

## Public APIs / Interfaces

本波不新增新的启动回执类型，保持现有响应资源类型，但改变其语义为“已受理后的即时投影”：

- `POST /api/runtime/sessions/{sessionId}/messages`：仍返回 `ConversationSession`，但不再代表 assistant 首结果已产生
- `POST /api/tasks`：仍返回 `TaskInstance`，表示任务/workflow 已受理
- `PATCH /api/workflows/{workflowId}/human-action`：仍返回 `WorkflowInstance`，表示人工动作已提交并进入恢复流程
- `GET /api/workflows/{workflowId}` / `GET /api/workflows`：返回体新增 `agentTurnState`
- `WorkflowContracts.WorkflowResult`、OpenAPI `WorkflowInstance`、TS runtime types 同步新增 `agentTurnState`

## Test Plan

- API 单测：
  - `sendMessage` 不再调用等待式 gateway；返回后 task/workflow 为 `RUNNING`，session 含占位 assistant 消息
  - `launchTask` 立即返回已受理 task
  - `handleHumanAction` signal 成功后立即返回恢复中的 workflow
  - start/signal 失败时投影正确落 `FAILED` 或保留原等待态
  - `refreshRunningWorkflows` 能把 `agentTurnState`、最终回复、human 状态同步回投影
- Gateway 单测：
  - 删除首结果轮询逻辑，只验证 `WorkflowClient.start(...)` 和 `submitHumanAction(...)` 发出即可
- Repository 单测：
  - `agent_turn_state` 的 JSONB 读写、空默认值、兼容旧行
- Worker / contracts 单测：
  - `WorkflowResult` 序列化包含 `agentTurnState`
  - JVM/TS/OpenAPI 枚举和值域一致
- Python 单测：
  - `latestDecision` 和 `turnLogs` 从真实 agent turn 正确出现在 `WorkflowResult`
  - `TOOL_CALL / SKILL_READ / HUMAN_HANDOFF / FINAL` 四种决策都能稳定序列化
- Web 单测：
  - 发送消息后 UI 立即进入“处理中”而不是等待长请求
  - workflow 页正确显示 `agentTurnState`
  - 轮询刷新后占位 assistant 消息被最终回复覆盖

## Assumptions

- 第一波只做轮询，不引入 SSE/WebSocket 基建
- 不考虑兼容性保留；允许直接重命名 gateway 方法和更新 OpenAPI 语义
- 不新增完整决策审计表；当前只把最新 `agentTurnState` 持久化到 workflow 投影
- 运行态/决策共享类型进 `packages/contracts`；Web 只对第一波涉及的 runtime/decision 类型直接切共享包，其余 catalog 类型暂不扩大重构范围
- `agent_turn_state` 采用单列 `jsonb` 落库，而不是拆多列
