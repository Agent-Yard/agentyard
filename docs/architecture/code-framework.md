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
- 资源管理：表达资源归属、共享范围、版本体系、最新版本/生效版本语义和绑定锚点
- 运行时会话：由调用方选择一个助手后持续对话，并沉淀任务与流程轨迹
- 助手发布：发布时冻结当前资源绑定版本，生成可回溯的发布快照

## 应用划分

- `apps/web`：控制台前端，承接控制台页面与 mock 角色切换
- `apps/api`：控制面 API，负责配置态与运行态主接口
- `apps/worker`：Temporal workflow worker，承接长流程托管与人工恢复
- `apps/agent-runtime`：Python 执行运行时，负责图编排、资源调用和节点推进
- `packages/contracts-jvm`：JVM 侧共享运行契约
- `packages/contracts`：OpenAPI 与前端共享 contract
- `infra/local`：本地依赖启动

## 前端导航

控制台导航按四个一级分区组织，并在分区下展开二级页面：

- `平台设计`：业务域、业务场景
- `助手构建`：助手配置、智能体、编排设计
- `资源与发布`：资源目录、资源新建
- `运行与观测`：会话运行、流程观测

## 后端模块

- `auth-domain`：当前用户、角色策略、mock 登录
- `tenant-domain`：保留租户边界，当前实现默认单租户
- `scenario-domain`：业务域、业务场景、助手、智能体
- `resource-domain`：知识库、Tool、LLM、Prompt 模板及资源版本配置
- `runtime-domain`：任务、流程、节点、人工介入
- `release-domain`：草稿、发布、快照冻结与运行锚点
- `shared-kernel`：公共枚举、错误码、审计字段、上下文

## 运行链路

当前运行链路已经是“发布快照驱动的单助手多智能体图编排”：

1. API 基于助手发布快照构建运行时 `AssistantRunSnapshot`
2. Temporal workflow 调用 Python runtime `start`
3. Python runtime 按 graph snapshot 动态执行 `START / AGENT / HUMAN / END`
4. 若命中 `HUMAN` 节点，则返回 checkpoint 与 human task，workflow 等待 signal
5. 收到人工动作后，workflow 调用 runtime `resume`
6. 编排继续向后执行直到 `END` 或失败

## 当前实现策略

- LLM、知识库、Tool provider 采用轻量 adapter，并保留 `demo.local` 演示闭环
- 资源按“资源头 + 版本”建模，智能体绑定时必须显式锚定资源版本
- 助手切换到 `PUBLISHED` 时会冻结资源版本、agent 执行配置和编排图快照，作为后续运行和审计的稳定锚点
- 前端资源区拆分为“资源目录”和“资源新建”两页
- `资源目录`：聚焦资源清单、详情、版本流转、生效版本切换和结构化引用分析
- `资源新建`：按知识库、Tool、LLM、Prompt 模板四种蓝图维护结构化初始版本配置
- 认证采用本地 mock 用户，不接真实 OIDC
- 持久化采用 JSONB catalog store，工作流支持人工节点暂停恢复
- 控制面 API 优先提供演示闭环与前端真实接口消费
- 前端在保留运行态页面的同时，强化了“智能体编排页”“资源目录页”“资源新建页”作为当前主入口
- 当前系统层不做跨助手自动切换；一次会话只绑定一个助手，由调用方显式选择

## 当前边界

- 认证仍以 mock 方案为主，真实 OIDC 尚未接入
- 部分运行态对象仍在 API 内存中维护，未完全持久化
- workflow 启动链路仍同步等待首个结果，尚未改为异步订阅式观测
- 资源执行层优先保证本地联调和演示闭环，生产级安全治理仍需补齐

## 版本基线

当前代码按仓库内已落地的版本线组织：

- Java toolchain：25
- Spring Boot：4.0.1
- Vue：3.5
- Vite：8
- TypeScript：5.9
- Temporal SDK：1.32.1
- FastAPI：0.115.12
- LangGraph：0.2.53
- PostgreSQL / Redis / MinIO / Temporal：通过本地 Docker 依赖接入
