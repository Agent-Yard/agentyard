# Lynxus 当前代码框架

## 阶段定位

当前代码库已经不是“只验证概念能否成立”的最小 MVP，而是一个可本地联调、具备真实运行编排能力的平台原型。
更贴切的描述是：

- 单租户默认的企业智能体平台原型
- 发布快照驱动的单助手多智能体运行系统
- 面向后续持久化、权限、可观测和生产化治理扩展的代码底座

## 当前重心

当前实现主要围绕配置态、发布态与运行态三条主线：

- 智能体编排：表达助手内部节点、交接顺序和资源依赖
- 资源管理：表达资源归属、共享范围、版本体系、最新版本 / 生效版本语义和绑定锚点
- 运行时会话：由调用方选择一个助手后持续对话，并沉淀任务与流程轨迹
- 助手发布：发布时冻结当前资源绑定版本，生成可回溯的发布快照

## 应用划分

- `apps/web`：Vue 控制台，承接配置态页面、运行态页面和开发态用户会话展示
- `apps/api`：Spring Boot 控制面 API，负责目录、发布、会话和运行实例聚合
- `apps/worker`：Temporal workflow worker，负责长流程托管与人工恢复
- `apps/agent-runtime`：Python 执行运行时，负责图编排、资源调用和节点推进
- `apps/knowledge-service`：Python 知识服务，负责知识源对象、导入任务、文档切片、索引快照与检索数据
- `packages/contracts-jvm`：JVM 侧共享 workflow / runtime 契约
- `packages/contracts`：TypeScript 合同类型与 OpenAPI 文档
- `scripts`：本地开发启动脚本与环境变量装载
- `infra/local`：本地 Docker 依赖

当前仓库的构建方式是混合式的：

- `apps/api`、`apps/worker`、`packages/contracts-jvm` 由根目录 Gradle 多项目管理
- `apps/web`、`packages/contracts` 由 pnpm workspace 管理
- `apps/agent-runtime` 独立用 Python 虚拟环境运行

## 前端导航

控制台导航按四个一级分区组织，并在分区下展开二级页面：

- `平台设计`：业务域、业务场景
- `助手构建`：助手配置、智能体、编排设计
- `资源与发布`：资源目录、资源新建
- `运行与观测`：会话运行、流程观测

## API 代码分区

`apps/api` 当前更接近按职责分包，而不是完整 DDD 模块化拆分：

- `catalog`：业务域、场景、助手、智能体、资源、资源版本、编排、发布快照
- `runtime`：会话、任务、工作流、人工动作、Temporal gateway
- `auth`：开发态用户会话接口
- `system`：健康检查和依赖状态接口
- `shared`：统一响应和异常处理
- `config`：Web 跨域等基础配置

目录数据当前通过 `JdbcCatalogRepository` 落到 PostgreSQL JSONB；知识服务的结构化存储使用独立数据库；运行态 `session / message / task / workflow / humanIntervention` 投影也已落到 PostgreSQL，并在 API 启动时对账 Temporal。

## Worker 与 Runtime 分工

- `apps/worker` 中的 `workflow` 包负责 Temporal workflow 与 activity 编排
- `apps/worker` 中的 `runtime` 包负责通过 HTTP 调用 Python `agent-runtime`
- `apps/agent-runtime` 负责解析发布快照、校验图、执行节点并返回 `WorkflowResult`

## 运行链路

当前运行链路已经是“发布快照驱动的单助手多智能体图编排”：

1. API 基于助手发布快照构建运行时 `AssistantRunSnapshot`
2. Temporal workflow 调用 Python runtime `start`
3. Python runtime 按 graph snapshot 动态执行 `START / AGENT / HUMAN / END`
4. 若命中 `HUMAN` 节点，则返回 checkpoint 与 human task，workflow 等待 signal
5. 收到人工动作后，workflow 调用 runtime `resume`
6. 编排继续向后执行直到 `END` 或失败

## 当前实现策略

- LLM、知识库、Tool provider 采用轻量 adapter，运行时只走真实 provider 调用
- 知识库治理采用显式异步任务模型：文件 / URL 导入、解析切片、索引快照构建、失败重试与检索验证分层治理
- 资源按“资源头 + 版本”建模，智能体绑定时必须显式锚定资源版本
- 助手切换到 `PUBLISHED` 时会冻结资源版本、agent 执行配置和编排图快照，作为后续运行和审计的稳定锚点
- 前端资源区拆分为“资源目录”和“资源新建”两页
- `资源目录`：聚焦资源清单、详情、版本流转、生效版本切换和结构化引用分析
- `资源新建`：按知识库、Tool、LLM、Skill 四种蓝图维护结构化初始版本配置
- `SKILL` 资源承担智能体按需读取的技能提示，不再使用独立 Prompt Template 资源
- 认证采用本地开发态用户会话，不接真实 OIDC
- 持久化采用 JSONB catalog store，工作流支持人工节点暂停恢复
- 控制面 API 优先提供真实接口消费，不再提供内置 demo 数据闭环
- 前端在保留运行态页面的同时，强化了“智能体编排页”“资源目录页”“资源新建页”作为当前主入口
- 当前系统层不做跨助手自动切换；一次会话只绑定一个助手，由调用方显式选择

## 当前边界

- 认证仍以 mock 方案为主，真实 OIDC 尚未接入
- 运行态投影已持久化到 PostgreSQL，但当前仍是投影模型而非完整 event log
- workflow 启动链路已改为异步受理后返回，由控制台轮询收口运行结果
- 资源执行层优先保证本地联调和演示闭环，生产级安全治理仍需补齐
- MinIO / pgvector 已进入知识导入与检索正式链路，但线上职责、备份与监控仍需继续补齐

## 版本基线

当前代码按仓库内已落地的版本线组织：

- pnpm：9.12.0
- Java toolchain：25
- Spring Boot：4.0.1
- Vue：3.5.13
- Vite：8
- TypeScript：5.9
- Temporal SDK：1.32.1
- FastAPI：0.115.12
- Uvicorn：0.34.0
- LangGraph：0.2.53
- Ant Design Vue：4.2.6
- PostgreSQL / Redis / MinIO / Temporal：通过本地 Docker 依赖接入
