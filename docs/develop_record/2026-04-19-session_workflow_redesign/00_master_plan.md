# Session Workflow 重构执行总计划

## 0. 任务定义

- 任务名称：Session Workflow 大重构
- 唯一设计基准：`docs/develop_record/session_workflow_redesign.md`
- 总体原则：
  - 现有实现凡与设计文档冲突，直接按设计重做
  - 不考虑向前兼容、数据兼容、接口兼容、灰度迁移或历史数据保留
  - 禁止对旧 `assistant-run / orchestration graph / LangGraph` 模型做补丁式延续
  - 所有阶段推进、暂停、恢复、验收必须先回到本目录文档

## 1. 当前结论摘要

- 最终系统根模型已切换为：
  - catalog 侧：`assistant(primaryAgentId, ownerPolicy, sessionPolicy, playbookPolicy)` + owner-capable agents + playbooks
  - worker 侧：`session workflow + playbook child workflow + activities`
  - runtime 侧：单 agent 单轮推理，无状态，返回 `AgentTurnResult`
  - API 侧：`session + session_event + playbook_run` 为核心持久化与观测模型
  - web 侧：围绕 `session event / current owner / playbook run / handoff` 展示
- 执行状态：
  - `docs/doing` 五份执行文件已形成闭环
  - 主工程 `apps/` 与 `packages/` 下旧 `AssistantRunWorkflow / LangGraph / orchestration runtime` 有效代码已清空
  - `agent-runtime` 已补齐真实的 LLM act/tool-result/final-decision 循环
  - tool / skill 已完成从前端配置到 release 冻结再到 runtime 真消费的闭环
  - knowledge 检索边界已按“runtime 远程调用 knowledge-service”与设计、实现、测试完成对齐
  - knowledge builtin tools 已恢复为按实际有效 binding 的按需注入语义，并完成验证
  - 当前状态已重新关闭为 final：逐句审计发现的 playbook 执行、activity retry、投影持久化与 prompt runtime context 缺口均已补齐
  - 最新实现决策已确认：
    - `STEP` 由 worker 通过受限 sandbox 执行
    - `TOOL_TASK` 不在 worker 直接执行，统一走 agent-runtime 的 tool/runtime capability 执行链
    - `session / session_event / playbook_run` 由 worker 主动持久化
    - worker 与 api 共用同一核心库，默认库名收敛为 `agentyard_core`
    - `sharedState` 最终口径固定为扁平 KV

## 2. 主干计划

### 2.1 系统级重构主线

#### 2.1.1 根模型替换

- 目标：
  - 用 `session-owner-playbook` 根模型替换现有 `assistant-run graph` 根模型
- 涉及模块：
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/api`
  - `apps/worker`
  - `apps/agent-runtime`
  - `apps/web`
- 设计依据：
  - 设计文档 §1, §2, §3, §4, §5, §6, §8
- 前置条件：
  - 完成差异分析并确认旧模型的所有入口、持久化与前端消费点
- 完成标准：
  - 主链路不再依赖 `AssistantRunWorkflow`、`GraphSnapshot`、`DecisionType`、`LangGraph`、`ResumeIntervention`
  - 新的共享契约、持久化模型、Temporal workflow 和前端类型全部切换到设计模型

#### 2.1.2 执行闭环建设

- 目标：
  - 确保本次重构任务本身可持续恢复、可核查、可审计
- 涉及模块：
  - `docs/doing`
- 设计依据：
  - 用户执行要求第 2/3/4/7/8 条
- 前置条件：
  - 无
- 完成标准：
  - 本目录 5 个执行文件持续同步，任何代码推进都能在此找到依据、进度与验证结果

### 2.2 模块级分解

#### 2.2.1 Contracts 与目录模型

- 目标：
  - 用新配置模型替换旧编排图和旧运行时契约，并删除残留旧 contracts/openapi/test 旁路
- 涉及模块：
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/api` catalog
  - `apps/web` catalog pages
- 设计依据：
  - 设计文档 §4, §6, §7, §8
- 前置条件：
  - 明确现有 assistant/agent/orchestration DTO 与发布快照生成路径
- 完成标准：
  - assistant/agent/playbook/session event/playbook run 的共享契约稳定
  - catalog 层不再以 orchestration graph 为核心配置模型
  - `packages/contracts`、`packages/contracts-jvm`、OpenAPI 与 catalog 测试中不再残留 `task/workflow/resume/orchestration graph` 旧语义死代码

##### 2.2.1.1 Assistant 配置模型改造

- 目标：
  - 增加 `primaryAgentId`、`ownerPolicy`、`sessionPolicy`、`replyPolicy`、`playbookPolicy`
- 涉及模块：
  - `apps/api` catalog
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/web`
- 设计依据：
  - 设计文档 §2.1, §4.1
- 前置条件：
  - assistant release snapshot 生成路径可调整
- 完成标准：
  - assistant 当前发布态和运行快照均包含新字段

##### 2.2.1.2 Agent 配置模型改造

- 目标：
  - 增加 owner 能力与动作白名单，去掉仅服务旧 graph 的隐式假设
- 涉及模块：
  - `apps/api` catalog
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/web`
- 设计依据：
  - 设计文档 §2.1, §2.2, §4.2
- 前置条件：
  - assistant 新模型已落定
- 完成标准：
  - agent 配置包含 `canOwnSession`、`allowedActions`、`switchableOwnerAgentIds`、`playbookIds`

##### 2.2.1.3 Playbook 配置模型落地

- 目标：
  - 增加 playbook 作为独立强流程配置对象
- 涉及模块：
  - `apps/api` catalog
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/web`
- 设计依据：
  - 设计文档 §2.2, §3.3, §4.3, §4.4
- 前置条件：
  - 确认 playbook 节点与边配置字段
- 完成标准：
  - catalog 可持有 playbook 定义，release/runtime snapshot 可引用 playbook
  - `SessionRuntimeService` 启动 session 时能够把 assistant 当前发布态内的 playbook 定义一并注入 workflow

##### 2.2.1.4 旧契约与旧编排残留清除

- 目标：
  - 清除共享契约、OpenAPI、catalog 测试与前端展示中仍残留的旧 workflow/orchestration 语义
- 涉及模块：
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `packages/contracts/openapi`
  - `apps/api`
  - `apps/web`
- 设计依据：
  - 设计文档 §1, §2, §4, §6, §8
- 前置条件：
  - `session-runtime` 已成为 API/web 唯一主入口
- 完成标准：
  - 旧 `/tasks`、`/workflows`、`ResumeIntervention`、`ConversationSession`、`ASSISTANT_ORCHESTRATION`、`AGENT_ORCHESTRATION_NODE` 等遗留语义不再保留于主工程

#### 2.2.2 Worker 重构

- 目标：
  - 实现 `session workflow + playbook child workflow + activity` 新编排结构
- 涉及模块：
  - `apps/worker`
  - `packages/contracts-jvm`
- 设计依据：
  - 设计文档 §2.3, §2.4, §2.5, §3.1, §3.3, §5, §8.2, §9
- 前置条件：
  - 新运行时契约已就位
- 完成标准：
  - `AssistantRunWorkflow*` 退场
  - 存在新的 session workflow 与 playbook child workflow
  - 支持 Update/Signal 混合入口、reevaluation、handoff、playbook wait/resume/completion
  - worker 能产出可同步到控制面的 `session event + playbook run + snapshot` 投影，而不再依赖旧 runtime projection 模型

##### 2.2.2.1 Session Workflow 状态机

- 目标：
  - 落地设计文档 §5 的权威状态与 §9 的主流程
- 涉及模块：
  - `apps/worker`
- 设计依据：
  - 设计文档 §3.1, §5, §9.1, §9.2, §9.3, §9.4, §9.5, §9.6
- 前置条件：
  - 新 activity 协议已确定
- 完成标准：
  - session workflow 成为唯一会话控制中心

##### 2.2.2.2 Playbook Child Workflow

- 目标：
  - 落地可挂起、可恢复、可返回结构化结果的 child workflow
- 涉及模块：
  - `apps/worker`
- 设计依据：
  - 设计文档 §3.3, §4.3, §4.4, §9.3, §9.5
- 前置条件：
  - playbook 配置模型已可读取
- 完成标准：
  - playbook 的 WAITING/RUNNING/终态与 session event、playbook run 持久化一致
  - `STEP` 与 `TOOL_TASK` 分层清晰：
    - `STEP` 通过 sandbox activity 执行脚本化节点
    - `TOOL_TASK` 通过 runtime activity 执行工具或 runtime capability

##### 2.2.2.3 Activity 分层

- 目标：
  - 把 agent turn、playbook step、playbook tool 分离成独立 activity
- 涉及模块：
  - `apps/worker`
  - `apps/agent-runtime`
- 设计依据：
  - 设计文档 §3.2, §3.3, §8.2
- 前置条件：
  - 新 contracts 定义完成
- 完成标准：
  - activity 边界清晰，workflow 内不直接执行动态脚本和外部能力
  - worker 内不直接执行业务 tool；`TOOL_TASK` 始终下沉到 runtime

#### 2.2.3 Agent Runtime 重构

- 目标：
  - 取消 LangGraph，把 Python runtime 收敛为“单 agent 单轮推理”服务
- 涉及模块：
  - `apps/agent-runtime`
  - `packages/contracts-jvm`
  - `packages/contracts`
- 设计依据：
  - 设计文档 §2.2, §3.2, §5.2, §10
- 前置条件：
  - AgentTurnRequest/Result 契约确定
- 完成标准：
  - runtime 不再消费 graph，不再处理 human node/resume checkpoint
  - runtime 只返回 `AgentDecision + sharedState`
  - runtime 必须真实消费由 assistant release 冻结后的 model / skill / tool descriptor

##### 2.2.3.1 Prompt 输入模型重构

- 目标：
  - 落地 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
- 涉及模块：
  - `apps/agent-runtime`
- 设计依据：
  - 设计文档 §3.2
- 前置条件：
  - 新请求模型确定
- 完成标准：
  - runtime prompt 拼装符合设计，不再使用旧 `ConversationContext`/graph node 语义
  - `sharedState` 按扁平 KV 暴露，但运行时上下文需按 memory window / 字节预算 / 最小暴露原则裁剪

##### 2.2.3.2 AgentDecision 输出模型重构

- 目标：
  - 用 `REPLY / NO_REPLY / SWITCH_OWNER / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF` 替换旧 `DecisionType`
- 涉及模块：
  - `apps/agent-runtime`
  - `packages/contracts`
  - `packages/contracts-jvm`
- 设计依据：
  - 设计文档 §2.2, §3.2, §5.2
- 前置条件：
  - 新决策 schema 确定
- 完成标准：
  - runtime 输出与 session workflow 校验逻辑一致

##### 2.2.3.3 Tool / Skill 真消费闭环

- 目标：
  - 让前端配置的 tool / skill 在 assistant release 冻结后，以真实 descriptor 注入 runtime 并被实际消费
- 涉及模块：
  - `apps/api`
  - `apps/worker`
  - `apps/agent-runtime`
  - `packages/contracts`
  - `packages/contracts-jvm`
- 设计依据：
  - 设计文档 §3.2, §4.2, §8.2, §10
- 前置条件：
  - assistant release 已冻结 `agents + resources`
- 完成标准：
  - `SessionRuntimeService` 基于 `release.agents + release.resources` 生成运行时 agent 快照
  - `AgentTurnRequest` 携带真实 model / skill / tool descriptor，而不是仅有 resource id
  - tools 以模型原生 function/tool calling 形式暴露，并按 provider config 执行
  - skills 以“目录先暴露、详情按需加载”的模式被模型真实消费

##### 2.2.3.4 Knowledge 远程检索闭环

- 目标：
  - 在保持统一 knowledge store 的前提下，由 runtime 远程调用 knowledge-service 完成在线检索与 chunk read
- 涉及模块：
  - `apps/agent-runtime`
  - `apps/api`
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `docs/architecture`
- 设计依据：
  - 设计文档 §3.2, §8.1
- 前置条件：
  - assistant release 已冻结 knowledge binding snapshot
- 完成标准：
  - 设计文档不再要求“knowledge-service 整体并入 runtime”，而是明确 runtime 远程调用检索
  - `AgentTurnRequest` 携带当前 owner 的冻结 knowledge binding
  - runtime 暴露 builtin `knowledge_search / knowledge_read`
  - builtin knowledge tools 通过 internal API 远程调用 knowledge-service

#### 2.2.4 API 与持久化重构

- 目标：
  - 用 `session + session_event + playbook_run` 取代旧运行态投影模型
- 涉及模块：
  - `apps/api`
  - `packages/contracts`
  - DB migration
- 设计依据：
  - 设计文档 §2.4, §2.5, §6, §7, §9
- 前置条件：
  - 新 workflow 契约与事件模型已确定
- 完成标准：
  - API 入口、仓储、DTO、数据库结构、对账逻辑全部转向新模型

##### 2.2.4.1 Runtime API 入口改造

- 目标：
  - 提供用户消息提交、session 查询、session event 查询、playbook run 查询、恢复/回调/handoff 解除入口
- 涉及模块：
  - `apps/api`
  - `packages/contracts`
  - OpenAPI
- 设计依据：
  - 设计文档 §2.5, §6, §7
- 前置条件：
  - runtime service/repository 新模型可用
- 完成标准：
  - API 契约与设计文档一致

##### 2.2.4.2 运行态持久化模型改造

- 目标：
  - 新建或替换 session、session_event、playbook_run 持久化结构
- 涉及模块：
  - `apps/api`
  - DB migration
- 设计依据：
  - 设计文档 §6
- 前置条件：
  - 事件类型与字段稳定
- 完成标准：
  - 运行态不再以 `workflow_instance/outputMessages/resume_intervention` 为中心

##### 2.2.4.3 投递串行化与 guardrail

- 目标：
  - 在 API 落地 session 粒度串行投递与新建 session 加锁策略
- 涉及模块：
  - `apps/api`
- 设计依据：
  - 设计文档 §2.4, §2.5, §9.1, §9.6
- 前置条件：
  - 新 session lookup/create 流程已明确
- 完成标准：
  - 同一活跃 session 不会并发投递消息；draining 期间不会抢先新建 session workflow

#### 2.2.5 Web 重构

- 目标：
  - 从 workflow/debug 视角切到 session event / playbook / handoff 视角
- 涉及模块：
  - `apps/web`
  - `packages/contracts`
- 设计依据：
  - 设计文档 §6, §7
- 前置条件：
  - 新 API 与共享类型稳定
- 完成标准：
  - runtime 页面能展示 session 事件流、当前 owner、playbook 状态、handoff 状态
  - catalog 页面支持新 assistant/agent/playbook 配置

## 3. 阶段划分

### 阶段 A：任务框架与差异盘点

- 目标：
  - 建立 `docs/doing`
  - 完成“现状 vs 设计”差异分析
- 状态：已完成
- 完成标准：
  - `00~04` 五份文档首版完成
  - 已确认需要重做的根模型边界

### 阶段 B：共享模型与 catalog 重构

- 目标：
  - 先替换 contracts 和 catalog 配置模型
- 状态：已完成
- 完成标准：
  - 新 assistant/agent/playbook/runtime contracts 已落地
  - playbook 不再只是 agent 上的 `playbookIds` 字段，catalog 中存在可发布、可快照化的独立定义对象

### 阶段 C：worker 与 agent-runtime 主链路重构

- 目标：
  - 建成新 session workflow、playbook workflow 与 agent turn runtime
- 状态：已完成
- 完成标准：
  - 旧 `AssistantRunWorkflow + LangGraph` 主链路不再是运行依赖
  - `PlaybookWorkflow` 不再是单次 resume 即成功的骨架，而是能按节点类型执行、等待、恢复并返回终态结果
  - `SessionWorkflow` 的 reevaluation、handoff、resume/callback 幂等语义符合设计文档 §3.3 / §5 / §9
  - `agent-runtime` 不再依赖命令式占位规则，而是基于 prompt/runtime messages/capabilities 生成单轮决策

### 阶段 D：API/DB/runtime projection 重构

- 目标：
  - 切换 API 与持久化模型，完成对外运行态语义更新
- 状态：已完成
- 完成标准：
  - `session event + playbook run` 成为唯一运行态观测主线

### 阶段 E：web 收口、验证与验收

- 目标：
  - 收口前端展示、编译测试、文档核查
- 状态：已完成
- 完成标准：
  - console 不再暴露 `orchestration / workflow resume / runtime tasks` 旧模型页面
  - runtime 页面改为 `session event / current owner / playbook run / handoff` 视角
  - playbook 已纳入独立管理页、OpenAPI/TS contracts 和 object reference / deletion preview 治理
  - API 已补齐按设计要求的 `sessionId / 对话绑定键` 串行投递锁
  - `agent-runtime` 已具备真实的 LLM act/tool-result/final-decision 循环，而不是单次 one-shot 决策
  - 通过最终 checklist

## 4. 执行约束

- 任何阶段开始前，先更新 `01_gap_analysis.md` 或 `02_execution_log.md`
- 任何结构性偏差发现后，先改文档再改代码
- 不修改 `docs/develop_record/`
- 不允许把旧运行时逻辑作为“暂存兼容代码”留在仓库内；凡被新主路径替代的旧逻辑，必须进入删除范围
- 若设计文档存在无法唯一推导的冲突，必须在 `03_open_questions.md` 留痕，并在必要时暂停提问
- 每当完成一个阶段任务，必须先执行该阶段自检：
  - 更新 `docs/doing` 的计划状态、差异分析、执行记录与验收项
  - 运行该阶段对应编译、测试和必要集成验证
  - 检查实现是否与设计文档、contracts、上下游调用保持一致
  - 检查是否引入新的结构性坏味道，例如超大类、超长函数、职责漂移、跨模块耦合失控
- 阶段自检通过后，不等待人工再次催促，必须直接进入下一阶段
- 该循环持续执行，直到 `docs/doing` 进入最终闭环状态，而不是停在某个局部阶段完成点
- 当前主干顺序固定为：
  - 先收口最终设计差异
  - 再完成最终编译/测试/搜索核查
  - 最后把 `04_final_checklist.md`、其余执行文件与代码状态一起闭环

## 5. 阶段闭环规则

- 阶段完成 ≠ 工作暂停
- 每一阶段的结束动作固定为：
  - `更新 docs/doing`
  - `执行验证`
  - `做一致性与结构检查`
  - `若通过则立即切换到下一阶段`
- 只有当 `04_final_checklist.md` 与其他执行文件一起达到最终闭环时，才允许把本次大重构判定为结束
