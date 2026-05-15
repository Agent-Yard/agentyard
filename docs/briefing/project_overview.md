# Lynxus 项目汇报说明

## 1. 项目定位

Lynxus 当前定位为企业智能体平台 Alpha 原型，目标不是做单点问答，而是把治理、发布、运行和观测做成一套可持续演进的系统底座。

当前代码库已经形成从：

- 控制面治理
- 资源与知识治理
- 助手发布冻结
- session workflow 长流程托管
- owner agent 单轮推理
- playbook 强流程执行
- session event / playbook run 运行观测

的一条完整主链。

更准确的阶段判断是：

- 默认单租户的企业智能体平台原型
- 可本地联调、可真实执行的 Alpha 阶段产品骨架
- 已具备发布锚点、运行持久化、人工接管与恢复闭环的系统原型

当前仍不应定义为生产平台。多租户治理、完整审计、细粒度观测和正式生产运维能力仍未闭环。

## 2. 项目要解决的问题

企业智能体建设的难点通常不在模型本身，而在系统化落地能力不足，主要表现为：

- 智能体、知识、工具、模型配置分散，缺少统一治理入口
- 运行直接依赖草稿配置，缺少稳定发布锚点
- 多角色协作链路不透明，运行结果难回溯
- 自动化、人工处理和站外交互缺少统一的长流程托管与恢复机制
- 资源复用、版本控制、引用影响分析能力薄弱

Lynxus 当前的解法，是把这些问题收束到统一平台结构中：

- 用业务域、业务场景、助手、智能体、playbook 组织治理结构
- 用 Knowledge Base、Tool、LLM Model、Skill 组织能力资产
- 用发布快照冻结真正参与运行的资源版本和流程定义
- 用 `SessionWorkflow + owner agent + PlaybookWorkflow` 承担运行主链
- 用 `session / session_event / playbook_run` 表达运行事实和观测入口

## 3. 当前核心运行模型

当前最关键的变化，是运行主线已经收敛为 `session workflow` 模型。

### 3.1 两层能力抽象已经明确

- `Owner Agent`
  - 负责接收用户消息或系统触发
  - 执行单轮推理
  - 决定回复、切换 owner、启动 playbook 或人工接管
- `Playbook`
  - 负责强业务流程
  - 以 Temporal child workflow 形式执行
  - 支持等待人工恢复、外部回调和结构化结果返回

不再保留旧的多节点 agent 图作为主运行抽象，也不再把轻量认知动作建模为独立 workflow。

### 3.2 Session 已成为唯一会话控制中心

- 一个 `session` 对应一条主 Temporal workflow
- assistant 显式配置唯一 `primaryAgentId`
- 运行时始终只有一个 `currentOwnerAgentId`
- 用户消息通过 `/api/session-runtime/messages` 进入当前 session；请求可携带 `sessionId` 发送到已有 session，或携带 `assistantId` 用首条消息启动 / 复用 session
- owner 只能返回以下控制动作：
  - `REPLY`
  - `NO_OP`
  - `SWITCH_OWNER`
  - `RUN_PLAYBOOK`
  - `SESSION_HUMAN_HANDOFF`
  - `SECURITY_BLOCK`

### 3.3 运行观测主模型已经切换

运行态权威投影已经收敛为：

- `session_runtime_session`
- `session_runtime_event`
- `session_runtime_playbook_run`

因此当前运行页观察的核心对象也已经变成：

- 当前 owner
- 当前 shared state
- session event 时间线
- playbook run 列表
- handoff / idle / draining 等会话状态

旧的 `TaskInstance / WorkflowInstance / HumanCheckpoint / ResumeAction` 已从当前主工程的 schema 与 generated surface 清理；后续只按 session runtime 模型演进。

## 4. 当前已落地能力

### 4.1 控制面与治理能力

- 业务域、业务场景、助手、智能体、playbook、知识库、资源的配置管理
- 控制台五条主线导航：平台设计、助手构建、知识库、能力资源、运行与观测
- 浏览器认证已切到 API 托管 Session，默认走 OIDC 登录入口，本地开发保留 bootstrap 登录旁路
- 平台角色模型已落地为 `PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER / BUSINESS_USER`

### 4.2 资源与知识治理能力

- 资源类型收敛为 `TOOL / LLM_MODEL / SKILL`
- 知识库作为独立一级治理对象，而不是助手附属字段
- 资源目录、资源新建、版本发布、生效版本、引用分析、删除影响预览
- 知识库工作台支持文件导入、URL 导入、导入任务、文档解析、切片、索引快照、检索验证、发布版本
- `PostgreSQL + pgvector + pg_trgm + tsvector` 已作为当前正式知识检索后端

### 4.3 发布与冻结能力

- 助手发布时冻结资源版本锚点
- 冻结默认模型绑定、知识发布绑定、agent 执行策略、owner policy、session policy 和 playbook 定义
- 运行只基于发布版 assistant release 启动

### 4.4 运行与编排能力

- `SessionWorkflow` 管理 owner、shared state、handoff、idle timer 和 playbook 生命周期
- Python `agent-runtime` 负责单个 owner agent 的单轮推理
- `PlaybookWorkflow` 负责 `STEP / TOOL_TASK / HUMAN_TASK / EXTERNAL_INTERACTION / END` 节点遍历
- 已打通：
  - 用户消息受理
  - owner switch
  - playbook 启动
  - playbook 等待 / 恢复 / 完成
  - session 级人工接管

### 4.5 运行观测能力

- 单 session 查看当前 owner、active playbook、shared state、event 时间线和 playbook runs
- `OWNER_REPLY / OWNER_SWITCH / PLAYBOOK_WAITING / PLAYBOOK_COMPLETED / SESSION_HUMAN_HANDOFF_STARTED` 等事件可回看
- 运行态主投影已持久化到 PostgreSQL
- 前端运行主链已切到真实接口消费，不再回退到内置 mock 数据

## 5. 当前系统结构

当前仓库采用 monorepo 结构，核心服务划分如下：

- `apps/api`
  - Spring Boot 控制面服务
  - 负责目录数据、资源治理、知识库聚合、助手发布、认证会话和 `session-runtime` 查询入口
- `apps/channel-gateway`
  - Spring Boot Channel Provider 运行时
  - 负责入站事件归一化、出站投递与 provider 注册（飞书等）
- `apps/worker`
  - Temporal worker
  - 负责 `SessionWorkflow`、`PlaybookWorkflow` 和知识相关 workflow / activity
- `apps/knowledge-service`
  - Python 知识服务
  - 负责内容导入、文档解析、切片、索引快照构建、检索和按快照读取 chunk
- `apps/agent-runtime`
  - Python 运行时
  - 负责执行 owner agent 单轮推理，并通过内部接口完成 tool / skill / knowledge 调用
- `apps/web`
  - Vue 控制台
  - 提供配置治理、知识与资源管理、运行与观测界面

Tool Connector 与 Channel Provider 通过 `packages/extension-protocol` 定义的 Extension Plane 协议接入，业务系统的鉴权、签名、长连接细节不再泄漏到 Agent / Playbook / Session。

当前本地依赖包括：

- PostgreSQL：控制面与运行态投影、知识服务数据存储
- S3-compatible object storage：知识对象存储，本地/dev 使用 MinIO
- Redis：共享登录态、分布式锁、幂等和 session SSE replay / broadcast
- Temporal：session / playbook 长流程编排
- sandbox：playbook `STEP` 节点代码执行沙箱

## 6. 当前关键设计取舍

### 6.1 发布快照作为运行锚点

核心目的是解决配置漂移问题。运行时不直接读取助手草稿，而是统一使用发布冻结后的 release 配置。这样可以保证：

- 运行结果可回溯
- 资源版本可定位
- 行为边界可审计

### 6.2 将 session workflow 提升为唯一会话控制中心

会话主状态、owner、handoff、idle timer、pending reevaluation 和 active playbook 全部收口到 `SessionWorkflow`，避免控制权分散在 API、runtime 和中间状态表之间。

### 6.3 将 playbook 下沉为强流程子工作流

复杂业务流程由 `PlaybookWorkflow` 作为 child workflow 承担；owner 只负责决策是否调用和如何消费结果，不直接在推理回合内执行长流程。

### 6.4 将工具与知识能力留在 agent-runtime 内部循环

知识检索、tool calling、skill 读取都属于 owner 单轮推理内部步骤，不再提升为 session workflow 的显式动作。这让运行边界更清晰：

- session workflow 只关心控制动作和状态机推进
- agent-runtime 只关心单轮推理与能力调用

### 6.5 将命令受理与结果观测解耦

运行入口当前统一是“提交命令，然后从 session detail / session SSE 观察结果”，API 不再同步等待首个业务结果。这样让 API、worker 和前端的职责边界更稳定，也让跨实例流式观测可以独立演进。

## 7. 当前边界与不足

当前项目虽然主链已经成立，但仍明确保留原型边界，主要包括：

- 默认单租户，复杂租户治理仅保留模型边界
- 认证已具备 API Session 与 OIDC 边界，但开发态仍保留 bootstrap 登录旁路
- 运行态当前以 session 投影 + event 时间线为主，还不是完整审计账本
- session 级 SSE 已落地，但长 session 分页、派生视图和操作台收口仍未补齐
- Web 运行页尚未补全人工接管、human resume、external callback 的操作面板
- external interaction 的 provider adapter、签名校验和补偿治理仍未闭环
- 软删除、归档视图、版本差异和发布影响展示仍未完整补齐
- 审计、安全隔离、灰度发布、SLO、成本治理尚未形成生产级体系

因此，当前项目适合定义为平台原型或 Alpha 骨架，不适合定义为生产可交付平台。

## 8. 下一阶段重点

### 8.1 补强运行观测

- 在现有 session 级 SSE 基础上补长 session 分页、过滤和派生视图
- 继续降低运行页 fallback 轮询成本
- 强化事件时间线与 playbook waiting / resume 的即时反馈

### 8.2 完善人工操作链路

- 补齐 handoff、human reply、human resume、external callback 的控制台操作面板
- 为等待态和人工接管态提供更明确的 UI 收口

### 8.3 强化治理生命周期闭环

- 完善知识快照、资源版本和目录对象的生命周期治理
- 补齐软删除、归档视图、版本差异、发布影响展示等治理能力
- 继续强化统一引用分析与删除影响预览的覆盖范围

### 8.4 收敛生产化运行基座

- 明确 external interaction provider、回调鉴权、补偿与监控方案
- 收敛 pgvector、S3-compatible object storage 等依赖在正式架构中的职责
- 逐步补齐 CI、备份、恢复、容量规划和环境一致性能力

## 9. 汇报结论

Lynxus 当前已经完成从平台概念到可运行原型的关键跨越。项目已经不再停留在对象建模和方案讨论阶段，而是形成了企业智能体平台最关键的一条执行主链：

- 有治理结构
- 有资源体系
- 有知识治理
- 有发布快照
- 有 session workflow
- 有 owner agent 决策
- 有 playbook 强流程
- 有人工接管与恢复
- 有 session event 观测

当前最准确的判断是：平台骨架已经建立，主链已经闭环，生产化能力仍待补齐。
