# Lynxus 首版代码框架

## 目标

首版框架服务于 MVP，而不是一次性铺满企业级中台全貌。重点是搭出一套可本地运行、边界清晰、后续可扩展的代码底座。

当前优先级放在配置态与运行态两条主线：

- 智能体编排：表达助手内部节点、交接顺序和资源依赖
- 资源管理：表达资源归属、共享范围、版本体系、最新版本/生效版本语义和绑定锚点
- 运行时会话：由调用方选择一个助手后持续对话，并沉淀任务与流程轨迹
- 助手发布：发布时冻结当前资源绑定版本，生成可回溯的发布快照

## 应用划分

- `apps/web`：控制台前端，承接控制台页面与 mock 角色切换
- `apps/api`：控制面 API，负责配置态与运行态主接口
- `apps/worker`：工作流执行骨架，承接 Temporal workflow/activity
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
- `tenant-domain`：保留租户概念，首版默认单租户
- `scenario-domain`：业务域、业务场景、助手、智能体
- `resource-domain`：Skill、MCP、知识库、资源版本配置及绑定关系
- `runtime-domain`：任务、流程、节点、人工介入
- `release-domain`：草稿/发布态占位
- `shared-kernel`：公共枚举、错误码、审计字段、上下文

## 运行链路

当前运行链路已经升级为“发布快照驱动的单助手多智能体图编排”：

1. API 基于助手发布快照构建运行时 `AssistantRunSnapshot`
2. Temporal workflow 调用 Python runtime `start`
3. Python runtime 按 graph snapshot 动态执行 `START / AGENT / HUMAN / END`
4. 若命中 `HUMAN` 节点，则返回 checkpoint 与 human task，workflow 等待 signal
5. 收到人工动作后，workflow 调用 runtime `resume`
6. 编排继续向后执行直到 `END` 或失败

## 当前实现策略

- LLM、知识库、MCP、Skill 采用轻量 adapter，并保留 `demo.local` 演示闭环
- 资源按“资源头 + 版本”建模，智能体绑定时必须显式锚定资源版本
- 助手切换到 `PUBLISHED` 时会冻结资源版本、agent 执行配置和编排图快照，作为后续运行和审计的稳定锚点
- 前端资源区拆分为“资源目录”和“资源新建”两页
- `资源目录`：聚焦资源清单、详情、版本流转、生效版本切换和绑定影响
- `资源新建`：按知识库、Skill、MCP 三种蓝图维护结构化初始版本配置
- 认证采用本地 mock 用户，不接真实 OIDC
- 持久化采用 JSONB catalog store，工作流支持人工节点暂停恢复
- 控制面 API 优先提供演示闭环与前端真实接口消费
- 前端在保留运行态页面的同时，强化了“智能体编排页”“资源目录页”“资源新建页”作为当前主入口
- 当前系统层不做跨助手自动切换；一次会话只绑定一个助手，由调用方显式选择

## 版本基线

首版代码按最新稳定版本线配置，而不是受当前本地安装版本限制：

- Java toolchain：25
- Spring Boot：4.0.1
- Vue：3.5
- Vite：8
- TypeScript：5.9
- PostgreSQL：17
- Redis：8
