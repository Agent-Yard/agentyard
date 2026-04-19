# Session Workflow 重构最终验收清单

## 1. 执行文件闭环

- [x] `00_master_plan.md` 已反映最终阶段状态
- [x] `01_gap_analysis.md` 已覆盖关键差异并与最终实现一致
- [x] `02_execution_log.md` 已记录各阶段实施、验证与偏差修正
- [x] `03_open_questions.md` 的问题已关闭或明确保留
- [x] `04_final_checklist.md` 已完成逐项核查
- [x] 各阶段完成后都已执行“自检 -> 文档同步 -> 验证 -> 自动进入下一阶段”的闭环动作

## 2. 设计文档逐节核查

### 2.1 目标与概述

- [x] 顶层运行模型已改为 `一个 session 对应一条主 Temporal workflow`
- [x] assistant 已显式配置 `primaryAgentId`
- [x] 运行时存在唯一 `currentOwnerAgentId`
- [x] 支持受控 `SWITCH_OWNER`
- [x] 支持 `RUN_PLAYBOOK` child workflow
- [x] `LangGraph` 已完全移除

### 2.2 核心设计决策

- [x] Owner 模型、白名单与单 turn 切换上限已实现
- [x] `REPLY / NO_REPLY / SWITCH_OWNER / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF` 已完整实现
- [x] playbook 活跃期间的消息并发策略已实现
- [x] session 生命周期、idle timer、guardrail、draining 语义已实现
- [x] Update/Signal 混合入口已实现

### 2.3 运行时架构

- [x] session workflow 负责 owner/sharedState/playbook/handoff/reevaluation
- [x] agent-runtime 只执行单 agent 单轮推理
- [x] playbook child workflow 负责强流程、等待点、恢复与终态
- [x] agent-runtime 已具备真实的 act/tool-result/final-decision 循环
- [x] 前端配置的 tool / skill 已以 release 冻结 descriptor 注入 runtime 并被真实消费
- [x] knowledge 检索边界已明确为“runtime 远程调用 knowledge-service”，并已在实现中闭环
- [x] PromptRuntimeMessages 已按 memory window / 字节预算 / 最小共享上下文组装
- [x] `STEP` 已通过 sandbox activity 执行
- [x] `TOOL_TASK` 已统一通过 runtime capability 执行，而不是 worker 直接执行业务 tool

### 2.4 配置模型

- [x] assistant 配置模型符合设计文档
- [x] agent 配置模型符合设计文档
- [x] playbook 配置模型符合设计文档
- [x] playbook 节点与边模型符合设计文档

### 2.5 Session Workflow 内部状态

- [x] session workflow 权威状态字段齐全
- [x] turn 结束通用逻辑已实现
- [x] 运行时校验与统一降级路径已实现
- [x] `RUN_PLAYBOOK` 已按设计校验 `playbookInput` 符合 `inputSchema`

### 2.6 数据模型

- [x] `session event` 数据模型已落地
- [x] `playbook run` 数据模型已落地
- [x] 事件类型与 payload 语义符合设计文档
- [x] session event / playbook run 已由 worker / child workflow 主动持久化，而不是依赖 API 查询时同步 projection
- [x] worker 与 api 已共用核心库，默认会话库口径已收敛为 `lynxus_core`

### 2.7 对外接口

- [x] API 已支持用户消息、session 查询、session event 查询、playbook run 查询、人工恢复、外部回调、handoff 解除
- [x] session 观测以 `session event` 为主
- [x] playbook 观测以 `playbook run` 为主
- [x] API 已按设计要求实现 `sessionId / 对话绑定键` 串行投递锁

### 2.8 模块架构

- [x] `apps/worker` 目录结构符合 session/playbook/activity 分层
- [x] `apps/agent-runtime` 角色符合单 agent 单轮推理服务
- [x] `apps/api`、`apps/web` 已匹配新模型
- [x] playbook step/tool activity 已按设计落到真实沙箱执行 / 工具调用分层

### 2.9 典型执行流程

- [x] 普通消息处理流程符合 §9.1
- [x] owner 切换流程符合 §9.2
- [x] playbook 执行与 reevaluation 流程符合 §9.3
- [x] human handoff 流程符合 §9.4
- [x] 人工恢复/外部回调流程符合 §9.5
- [x] idle 结束与新 session 流程符合 §9.6

### 2.10 第一阶段约束

- [x] 无自动回退 owner
- [x] agent turn 期间拒绝新消息
- [x] playbook 活跃期间禁止 `SWITCH_OWNER` 和二次 `RUN_PLAYBOOK`
- [x] playbook 终态统一并入 `PLAYBOOK_COMPLETED`
- [x] 校验失败统一降级路径已生效
- [x] agent-runtime 失败统一降级路径已生效
- [x] 无主动取消 playbook API
- [x] `sharedState` 为扁平 KV
- [x] 不使用 Continue-As-New
- [x] activity retry policy 与 playbook timeout/retry policy 已真正生效

## 3. 工程质量核查

- [x] 运行时实现未形成新的超大类/超长函数/跨模块耦合热点
- [x] worker、runtime、API、contracts、web 职责边界清晰
- [x] 相关测试已补齐或更新
- [x] OpenAPI / TS contracts / JVM contracts / API DTO 保持一致
- [x] 相关架构文档已同步

## 4. 收尾输出

- [x] 已完成项已明确列出
- [x] 未完成项已明确列出
- [x] 剩余风险已明确列出
- [x] 后续建议已明确列出

## 5. 最终结论

### 已完成项

- session workflow / playbook workflow / agent-runtime / API / web / contracts 已全部切换到设计文档定义的新模型
- playbook 已具备独立 catalog 配置、发布冻结、运行注入、治理分析和前端管理能力
- API 已补齐 session 串行投递、对话级活跃 session 复用和 ended-session 收口
- tool / skill 已完成从前端配置到 runtime 真消费的闭环，且基于 release 冻结快照执行
- knowledge 检索已完成从 release 冻结 binding 到 runtime 远程调用 knowledge-service 的闭环
- knowledge builtin tools 已按实际有效 binding 按需注入，不对 disabled agent 暴露脏 capability
- 主工程旧 `AssistantRunWorkflow / LangGraph / orchestration runtime` 有效代码已清空

### 未完成项

- 无阻塞本次设计闭环的未完成项

### 剩余风险

- 当前串行锁为 API 进程内实现；若未来扩为多实例部署，需要升级为共享锁设施
- playbook 节点编辑当前以 JSON 配置为主，复杂流程编辑体验仍可继续提升
- `STEP -> sandbox` 已完成代码与配置接线，但本轮未在本地实际拉起 sandbox 容器执行端到端回归；若后续环境镜像或 API 版本漂移，需要补一次 live integration 校验

### 后续建议

- 若进入多实例部署阶段，优先补共享锁与活跃 session 争用监控
- 为 playbook 管理页补结构化节点编辑器与 schema 辅助校验
