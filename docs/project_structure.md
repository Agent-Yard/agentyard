# 项目结构与对象模型

## 1. Monorepo 结构

```text
apps/
  api/                  Spring Boot 控制面 API
  channel-gateway/      Channel Provider 运行时（飞书等渠道接入）
  worker/               Temporal workflow worker
  web/                  Vue + Ant Design Vue 控制台
  agent-runtime/        Python owner agent / playbook tool task 执行运行时
  knowledge-service/    Python 知识导入、快照构建与检索服务
packages/
  contracts/             OpenAPI 与 TypeScript 合同
  contracts-jvm/         JVM 侧 session / playbook / runtime 契约
  extension-protocol/    Extension Plane 协议（OpenAPI + JSON Schema + 契约样例）
  extension-sdk-jvm/     JVM SDK 骨架
  extension-sdk-python/  Python SDK 骨架
  persistence-jvm/       JVM 侧 PostgreSQL 持久化基座与 jOOQ schema
  python-common/         Python 服务共享工具库
  shared-redis-jvm/      JVM 侧共享 Redis keyspace / lock / pubsub / codec
deploy/
  common/             local / dev / test / prd 可共享的无密钥运行材料
  local/              本机联调 Docker Compose
  dev/                开发服务器常驻 Docker Compose
  test/               测试环境分模块发布模板
scripts/
  local/              本地源码直跑脚本
  dev/                开发服务器源码直跑脚本
  common/             环境变量装载与进程管理脚本
samples/
  extension-template/ 外部 extension service 的独立 Java 21 + Spring Boot 启动模板
docs/
  architecture/       当前架构与环境说明
  briefing/           项目背景资料
  todo/               仍然有效的待办分解
  develop_record/     历史留档，不作为当前实现基准
demo/                 演示素材目录，不参与当前主实现说明
```

## 2. 构建与运行基座

- Gradle 多项目：`apps/api`、`apps/channel-gateway`、`apps/worker`、`packages/contracts-jvm`、`packages/persistence-jvm`、`packages/shared-redis-jvm`、`packages/extension-sdk-jvm`
- pnpm workspace：`apps/web`、`packages/contracts`
- uv workspace：`apps/agent-runtime`、`apps/knowledge-service`、`packages/python-common`、`packages/extension-sdk-python`
- 本地依赖：PostgreSQL、MinIO（S3-compatible object storage 本地实现）、Redis、Temporal、sandbox
- PostgreSQL 启动时会自动准备 `lynxus_core`、`lynxus_channel_gateway`、`lynxus_knowledge`、`lynxus_agent_runtime` 四个库，按服务边界隔离

## 3. 治理主树

### 租户

定义：平台中的一级隔离与管理单元。
职责：隔离组织、权限、数据、配额。
当前状态：模型边界保留，当前实现按单租户默认展开。

### 业务域

定义：租户下按业务职能划分的治理单元。
职责：承载本域的场景、共享资源、治理规则。
主要使用者：平台管理员、域管理员。

### 业务场景

定义：面向具体业务目标的交付与使用单元。
职责：承载业务入口、助手归属与运行观测入口。
主要使用者：域管理员、场景负责人、业务用户。

### 助手

定义：业务场景下的一套协作配置与发布单元。
职责：组织 owner agent、playbook、默认模型绑定、发布冻结与运行入口。
主要使用者：域管理员、开发者。

### 智能体

定义：助手内部面向某类职责的执行角色。
职责：执行单轮推理，读取 skill / tool / knowledge，并决定回复、切换 owner、启动 playbook 或人工接管。
主要使用者：开发者。

### Playbook

定义：被 owner agent 调用的强业务流程单元。
职责：承载结构化输入输出、节点遍历、等待点、恢复和终态结果。
主要使用者：开发者。

### 资源

定义：可被助手、智能体引用的能力资产。
当前范围：Knowledge Base、Tool、LLM Model、Skill。
职责：提供可复用能力和稳定版本锚点。
主要使用者：开发者、域管理员。

注意：
资源不是简单树状从属，而是同时存在“归属、共享、绑定、发布冻结”四类关系。

---

## 4. 运行与发布横切面

### 绑定与共享

作用：解决“谁创建”和“谁使用”不是一回事的问题。

当前至少要表达 4 件事：

- 资源归属在哪
- 谁可见
- 谁可绑定使用
- 是否共享 / 是否需审批

当前实现里，这些关系主要体现在：

- `ownerType / ownerId`
- `shareScope`
- agent 执行策略中的 `skillResourceVersionIds / toolResourceVersionIds`
- 助手发布后的 release resources

### 发布快照

作用：解决运行时配置漂移问题。

当前发布快照会冻结：

- 助手发布版本
- `primaryAgentId`
- owner policy / session policy / playbook policy
- agent 执行配置
- 资源版本锚点
- playbook 定义

运行时只基于发布快照启动，不再以草稿临时快照承接主链。

### 运行实例

作用：表达会话控制、事件时间线和强流程执行态。

当前主要运行对象包括：

- `Session`
- `SessionEvent`
- `PlaybookRun`
- `sharedState`

其中：

- `Session` 持有当前 owner、handoff、idle deadline 与活跃 playbook 等权威状态
- `SessionEvent` 记录用户消息、owner 回复、owner switch、playbook 等待/恢复/完成、handoff 开始/结束等事实
- `PlaybookRun` 记录一次 playbook 执行实例的输入、结果、等待原因与终态

旧的 `Task / Workflow Instance / Human checkpoint` 已不再作为当前主设计基准。

---

## 5. 可执行结构

当前助手运行采用“两层能力模型”：

- `Owner Agent`
  - 接收用户消息或系统触发
  - 执行单轮推理
  - 通过最终 outcome 决定 `REPLY / NO_OP / SWITCH_OWNER / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF / SECURITY_BLOCK`
  - 可在任意 action 中携带完整 `replyMessage`
- `Playbook`
  - 由 owner 显式启动
  - 作为 Temporal child workflow 运行
  - 节点类型为 `STEP / TOOL_TASK / HUMAN_TASK / EXTERNAL_INTERACTION / END`
  - 可挂起、恢复并返回结构化结果

## 6. 代码与对象的一句话对应

- `apps/api` 负责治理与 session-runtime 聚合 API
- `apps/channel-gateway` 负责 Channel Provider 入站 / 出站 / 注册（飞书等）
- `apps/worker` 负责 `SessionWorkflow`、`PlaybookWorkflow` 和跨服务编排
- `apps/agent-runtime` 负责 owner 单轮推理与 playbook tool task 执行
- `apps/knowledge-service` 负责 source/job/document/snapshot 检索链路
- `apps/web` 负责治理控制台与运行观测
- `packages/extension-protocol` 定义 Extension Plane 协议（Tool Connector / Channel Provider 注册与契约）
- `samples/extension-template` 提供外部开发者复制使用的独立 extension service 模板

## 7. 一句话总结

当前可以把 Lynxus 理解为：

**一个以业务域和业务场景为治理入口、以发布快照为配置锚点、以 session-owner-playbook 为运行主线、以资源版本化与可恢复强流程为核心能力的平台原型。**
