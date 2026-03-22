# Lynxus 首版代码框架

## 目标

首版框架服务于 MVP，而不是一次性铺满企业级中台全貌。重点是搭出一套可本地运行、边界清晰、后续可扩展的代码底座。

当前优先级放在两条静态主线：

- 智能体编排：表达智能体组内部节点、交接顺序和资源依赖
- 资源管理：表达资源归属、共享范围和绑定关系

## 应用划分

- `apps/web`：控制台前端，承接 6 个 MVP 页面与 mock 角色切换
- `apps/api`：控制面 API，负责配置态与运行态主接口
- `apps/worker`：工作流执行骨架，承接 Temporal workflow/activity
- `packages/contracts`：OpenAPI 与前端共享 contract
- `infra/local`：本地依赖启动

## 后端模块

- `auth-domain`：当前用户、角色策略、mock 登录
- `tenant-domain`：保留租户概念，首版默认单租户
- `scenario-domain`：业务域、业务场景、智能体组、智能体
- `resource-domain`：Skill、MCP、知识库及绑定关系
- `runtime-domain`：任务、流程、节点、人工介入
- `release-domain`：草稿/发布态占位
- `shared-kernel`：公共枚举、错误码、审计字段、上下文

## 运行链路

知识问答升级流程固定为：

1. 问题接收
2. 知识检索
3. 回答生成
4. 升级判定
5. 结束或等待人工处理

## 当前实现策略

- 资源、知识库、MCP、Skill 采用 mock adapter
- 认证采用本地 mock 用户，不接真实 OIDC
- 持久化和工作流先给出结构、配置和接口层
- 控制面 API 优先提供演示闭环与前端真实接口消费
- 前端在保留运行态页面的同时，新增“智能体编排页”和“资源中心页”作为当前主入口

## 版本基线

首版代码按最新稳定版本线配置，而不是受当前本地安装版本限制：

- Java toolchain：25
- Spring Boot：4.0.1
- Vue：3.5
- Vite：8
- TypeScript：5.9
- PostgreSQL：17
- Redis：8
