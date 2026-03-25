# 技术路线

## 当前仓库已落地

- 前端：Vue 3.5 + TypeScript 5.9 + Vite 8 + Ant Design Vue 4
- 控制面后端：Java 25 + Spring Boot 4.0.1
- 工作流托管：Temporal SDK 1.32.1
- 执行运行时：Python + FastAPI + LangGraph
- 数据存储：PostgreSQL + Redis + MinIO
- 本地依赖编排：Docker Compose
- API 契约：OpenAPI + `packages/contracts` / `packages/contracts-jvm`

## 当前运行架构

- `apps/api`：控制面接口、目录数据、发布快照和运行实例聚合
- `apps/worker`：Assistant Run Workflow 的 Temporal worker
- `apps/agent-runtime`：基于发布图的节点执行运行时
- `apps/web`：配置态和运行态控制台

## 目标扩展方向

- 权限与身份：OIDC / 企业 IAM + 更细粒度授权策略
- 观测：OpenTelemetry + 指标 / 日志 / Trace 统一关联
- 发布治理：灰度、回滚、分批发布和稳定 CI/CD
- 资源执行层：更真实的知识库、Tool provider 和安全隔离
- 运行态：异步事件 / SSE / WebSocket 观测与更完整持久化
