# 技术路线

## 当前仓库已落地

- 前端：Vue 3.5 + TypeScript 5.9 + Vite 8 + Ant Design Vue 4
- 控制面后端：Java 25 + Spring Boot 4.0.1
- 长流程托管：Temporal SDK 1.32.1
- 执行运行时：Python + FastAPI
- 知识检索服务：Python + FastAPI + PostgreSQL `pgvector + pg_trgm + tsvector`
- 目录与运行态持久化：PostgreSQL JSONB
- 契约层：`packages/contracts` + `packages/contracts-jvm`
- 启动脚本：根目录 `scripts/*.sh` 统一装载环境变量并拉起各应用

## 当前运行架构

- `apps/api`：控制面接口、目录治理、发布快照组装、`session-runtime` 聚合与查询
- `apps/worker`：Temporal worker，承载 `SessionWorkflow` 主状态机和 `PlaybookWorkflow` child workflow
- `apps/agent-runtime`：无状态 owner agent 单轮推理服务，执行 `AgentTurnRequest -> AgentTurnResult`
- `apps/knowledge-service`：知识导入、索引构建、检索与按快照读取
- `apps/web`：配置态与运行态控制台，运行页围绕 session event / owner / playbook / handoff 组织

当前执行核心已经不是旧的图编排 runtime，而是一条：

`AssistantRelease -> SessionWorkflow -> owner agent turn -> optional PlaybookWorkflow -> session event / playbook run projection`

的闭环。

## 当前模型取舍

- 一个 `session` 对应一条主 `Temporal workflow`
- assistant 显式配置唯一 `primaryAgentId`，运行时维护唯一 `currentOwnerAgentId`
- owner agent 只负责单轮推理与动作决策，动作收敛为：
  - `REPLY`
  - `NO_REPLY`
  - `SWITCH_OWNER`
  - `RUN_PLAYBOOK`
  - `SESSION_HUMAN_HANDOFF`
- 强业务流程统一下沉为 `playbook` child workflow
- 轻量认知能力不再建模为独立 workflow，而是 owner agent 在单轮推理内通过 tool / knowledge / skill 完成
- 运行态权威模型收敛为 `session_runtime_session / session_runtime_event / session_runtime_playbook_run`

## 当前实现边界

- 已移除 `LangGraph`，不再以多节点 agent 图作为主运行模型
- 控制面公开的运行入口收敛到 `/api/session-runtime/...`
- 当前前端运行态仍以轮询拉取 session detail 为主，SSE 尚未落地
- external interaction 只作为 playbook 的等待点与恢复来源，不再保留独立 runtime 主模型

## 目标扩展方向

- 权限与身份：继续收敛真实 OIDC / IAM 与更细粒度授权
- 观测：在 `session event / playbook run` 投影之上补订阅式推送与审计账本
- 发布治理：灰度、回滚、版本 diff 与影响分析
- 运行基座：增强 playbook provider 生态、回调安全、运维告警和生产隔离
