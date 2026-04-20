# 当前对象结构

## 1. 治理主树

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

## 2. 运行与发布横切面

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

## 3. 可执行结构

当前助手运行采用“两层能力模型”：

- `Owner Agent`
  - 接收用户消息或系统触发
  - 执行单轮推理
  - 决定 `REPLY / NO_REPLY / SWITCH_OWNER / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF`
- `Playbook`
  - 由 owner 显式启动
  - 作为 Temporal child workflow 运行
  - 节点类型为 `STEP / TOOL_TASK / HUMAN_TASK / EXTERNAL_INTERACTION / END`
  - 可挂起、恢复并返回结构化结果

## 4. 一句话总结

当前可以把 Lynxus 理解为：

**一个以业务域和业务场景为治理入口、以发布快照为配置锚点、以 session-owner-playbook 为运行主线、以资源版本化与可恢复强流程为核心能力的平台原型。**
