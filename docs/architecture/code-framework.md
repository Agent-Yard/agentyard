# Lynxus 当前代码框架

## 阶段定位

当前代码库已经不是“验证概念是否成立”的最小 MVP，而是一个可本地联调、具备真实治理与运行主链的平台原型。

更贴切的描述是：

- 默认单租户的企业智能体平台原型
- 以 assistant release 为配置锚点的运行系统
- 以 `session workflow + owner agent + playbook workflow` 为核心主线的代码底座

## 当前重心

当前实现主要围绕三条主线：

- 配置治理：业务域、场景、助手、agent、playbook、知识库、资源
- 发布冻结：把运行必需的资源版本与流程定义冻结进 assistant release
- 运行执行：以 session 为中心承载 owner、shared state、playbook、handoff 与观测

## 应用划分

- `apps/web`：Vue 控制台，承接配置态页面、知识与资源工作台、运行态页面
- `apps/api`：Spring Boot 控制面 API，负责目录治理、发布、认证与 `session-runtime` 聚合查询
- `apps/worker`：Temporal worker，负责 `SessionWorkflow`、`PlaybookWorkflow` 与知识相关 workflow / activity
- `apps/agent-runtime`：Python 执行运行时，负责单个 owner agent 的单轮推理与 playbook tool task 执行
- `apps/knowledge-service`：Python 知识服务，负责知识源对象、导入任务、文档切片、索引快照与检索数据
- `packages/contracts-jvm`：JVM 侧共享 session / playbook / runtime 契约
- `packages/persistence-jvm`：JVM 侧共享 PostgreSQL persistence 基座，承载 jOOQ generated schema、shared store 与 JSONB helper
- `packages/shared-redis-jvm`：JVM 侧共享 Redis keyspace、JSON codec、Pub/Sub bus 与分布式锁
- `packages/contracts`：TypeScript 合同类型与 OpenAPI 文档
- `packages/python-common`：Python 服务共享基础模块
- `scripts`：本地开发启动脚本与环境变量装载
- `deploy/common`：local / dev / test / prd 可共享的无密钥运行材料
- `deploy/local`：本地 Docker 依赖
- `deploy/dev`：共享开发服务器一体化编排
- `deploy/test`：测试环境分模块发布模板

当前仓库的构建方式是混合式的：

- `apps/api`、`apps/worker`、`packages/contracts-jvm`、`packages/persistence-jvm`、`packages/shared-redis-jvm` 由根目录 Gradle 多项目管理
- `apps/web`、`packages/contracts` 由 pnpm workspace 管理
- `apps/agent-runtime`、`apps/knowledge-service`、`packages/python-common` 通过根目录 `uv` workspace 管理

## 前端导航

控制台导航当前按五个一级分区组织：

- `平台设计`：业务域、业务场景
- `助手构建`：助手配置、智能体、Playbook
- `知识库`：知识库目录、知识库新建
- `能力资源`：资源目录、资源新建
- `运行与观测`：会话运行

## API 代码分区

`apps/api` 当前按职责分包：

- `catalog`：业务域、场景、助手、智能体、playbook、资源、资源版本、发布快照
- `knowledge`：知识库治理、导入聚合、发布与检索验证
- `session`：`session-runtime` API、session repository、Temporal gateway、投递串行化
- `auth`：OIDC / bootstrap 登录与用户会话
- `system`：健康检查和依赖状态接口
- `shared`：统一响应、异常处理与通用基础设施
- `config`：Web 跨域等基础配置

Java core 数据访问当前以 `Flyway + jOOQ + packages/persistence-jvm` 为统一基线：

- `apps/api` 与 `apps/worker` 共用 `packages/persistence-jvm` 生成的 schema model 与 shared store
- `session runtime`、`platform_event`、`platform_user` 等共享表不再各自维护字符串 SQL
- `catalog` / `knowledge` 顶层治理对象已改成类型化列；只有嵌套策略、发布快照和配置片段保留 JSONB

运行态已经落到 PostgreSQL 的：

- `session_runtime_session`
- `session_runtime_event`
- `session_runtime_playbook_run`
- `platform_event`
- `platform_user`

控制面治理表当前同样落到 PostgreSQL typed schema：

- `catalog_domain`
- `catalog_scenario`
- `catalog_assistant`
- `catalog_agent`
- `catalog_playbook`
- `catalog_resource`
- `catalog_resource_versions`
- `catalog_assistant_releases`
- `knowledge_base`
- `knowledge_release`

## Worker 与 Runtime 分工

- `apps/worker/src/main/java/com/lynxus/worker/session`
  - 负责 `SessionWorkflowImpl`
  - 负责 `PlaybookWorkflowImpl`
  - 负责 session / playbook 持久化 activity
- `apps/worker/src/main/java/com/lynxus/worker/runtime`
  - 负责通过 HTTP 调用 Python `agent-runtime`、`knowledge-service` 与 sandbox
- `apps/agent-runtime`
  - 负责执行单个 owner agent 的一轮推理
  - 负责执行 playbook `TOOL_TASK`
  - 返回 `AgentTurnResult(decision, sharedState)` 或 `PlaybookToolTaskResult`

## 运行链路

当前运行链路已经切到 session workflow 模型：

1. API 创建 `session`，并以 assistant release 的 `primaryAgentId` 初始化 owner
2. `SessionWorkflow` 接收用户消息 Update，并维护 owner、shared state、handoff、idle timer、playbook 生命周期
3. worker 调用 Python runtime `/agent-turns/execute`
4. Python runtime 只执行当前 owner 的单轮决策，返回：
   - `REPLY`
   - `NO_REPLY`
   - `SWITCH_OWNER`
   - `RUN_PLAYBOOK`
   - `SESSION_HUMAN_HANDOFF`
5. 若 owner 启动 playbook，则由 `PlaybookWorkflow` 作为 child workflow 承担强流程
6. playbook 的等待、恢复和终态结果写入 `session event / playbook run`
7. 非 handoff 状态下，playbook 终态会触发 owner reevaluation 继续推进

## 当前实现策略

- 发布版 assistant release 是运行唯一配置锚点
- owner agent 的知识检索、tool calling、skill 读取都发生在 `agent-runtime` 内部推理循环
- Tool 是业务能力契约；Connector 是接入实现；Integration Account/Credential 是账号与密钥状态。Agent / Playbook 只看 Tool operation schema，不接触签名、cookie、secret 等 provider 协议细节
- Tool v1 内置 `SIMPLE_HTTP`、`BUSINESS_CODE_SECRET_HTTP`、`MCP` connector，执行仍在 `agent-runtime` 内；`agent-runtime` 通过 connector registry 分发执行，控制面通过 `ToolConnectorCatalog` 维护默认配置、账号规则和 operation mapping 默认值，Web 通过 `toolConnectors` 定义渲染表单字段和凭证模板
- Channel Provider 同样通过 Integration Account 复用账号与凭证治理；`channel-gateway` 内置 Feishu provider 使用飞书 Java SDK 长连接接收入站文本消息，只有 ACTIVE、开启 inbound、已绑定 assistant 且已关联 Integration Account 的 Feishu channel profile 才会初始化长连接 client，出站文本消息由 gateway-native adapter 直接调用 SDK，不请求 manifest 中的示例 `sendOutbound` HTTP path
- 新增 connector 时优先补齐三处边界：runtime connector 实现与 registry、API catalog 归一化定义、Web connector definition；Tool operation schema、Session 投影和 Integration Account 存储模型不应为单个厂商协议重复分支。具体开发流程见 `docs/architecture/tool-connector-development.md`
- playbook 只承担强业务流程，不重复承载 owner 推理
- `sharedState` 只承载认知性上下文，不承载 owner / handoff / playbook 生命周期这类操作性权威状态
- Java 持久化默认使用 jOOQ DSL，不再新增 `JdbcTemplate` / `NamedParameterJdbcTemplate` repository
- `session runtime`、`platform_event`、`platform_user` 等共享表逻辑进入 `packages/persistence-jvm`
- 控制面顶层治理对象采用类型化列建模，JSONB 只用于嵌套配置和发布快照
- 前端运行态页面围绕 `session event / owner / playbook / handoff` 组织，并优先通过 session SSE 接收更新
- 当前系统层不做跨 assistant 自动切换；一次 session 只绑定一个 assistant

## 当前边界

- 认证已经进入 OIDC-first 模式，但开发态仍保留 bootstrap 登录旁路
- session 运行态已落盘，但还没有订阅式更新
- Web 运行页仍缺人工操作面板
- 资源执行层优先保证本地联调和演示闭环，生产级安全治理仍需补齐
- S3-compatible object storage / pgvector 已进入知识导入与检索正式链路；本地与 dev 使用 MinIO，测试环境可切 AWS S3，线上职责、备份与监控仍需继续补齐

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
- Ant Design Vue：4.2.6
- PostgreSQL / MinIO / Temporal：通过本地 Docker 依赖接入；知识服务通过通用对象存储配置访问 MinIO 或 S3
