# 技术路线

## 当前仓库已落地

- 前端：Vue 3.5 + TypeScript 5.9 + Vite 8 + Ant Design Vue 4
- 控制面后端：Java 25 + Spring Boot 4.0.1
- 工作流托管：Temporal SDK 1.32.1
- 执行运行时：Python + FastAPI + LangGraph
- 目录持久化：PostgreSQL JSONB
- 本地依赖：默认 Docker Compose 拉起 PostgreSQL、MinIO、Temporal；知识服务直接使用 PostgreSQL 内的 `pgvector + pg_trgm + tsvector` 作为正式索引与召回后端
- 契约层：`packages/contracts` + `packages/contracts-jvm`
- 启动脚本：根目录 `scripts/*.sh` 统一装载环境变量并拉起各应用

## 当前运行架构

- `apps/api`：控制面接口、目录数据、发布快照和运行实例聚合
- `apps/worker`：Assistant Run Workflow 的 Temporal worker
- `apps/agent-runtime`：基于助手运行快照的节点执行运行时
- `apps/web`：配置态和运行态控制台

当前执行核心是一条“助手运行快照（发布版或草稿临时快照） -> Temporal -> Python runtime -> WorkflowResult”的闭环。

## 当前模型取舍

- 资源类型统一为 `KNOWLEDGE_BASE / TOOL / LLM_MODEL / SKILL`
- `SKILL` 资源承担智能体按需技能提示，不再保留独立 Prompt Template 资源
- 目录数据已持久化，运行态数据仍部分在内存中维护
- 认证仍是 mock，会话与运行观测先优先跑通产品主链路

## 目标扩展方向

- 权限与身份：OIDC / 企业 IAM + 更细粒度授权策略
- 观测：OpenTelemetry + 指标 / 日志 / Trace 统一关联
- 发布治理：灰度、回滚、分批发布和稳定 CI/CD
- 资源执行层：更真实的知识库、Tool provider 和安全隔离
- 运行态：异步事件 / SSE / WebSocket 观测与更完整持久化
