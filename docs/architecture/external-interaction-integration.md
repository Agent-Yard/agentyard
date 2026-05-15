# External Interaction 集成说明

本文件直接描述当前代码实现下，external interaction 在 `session-runtime` 体系中的位置、边界和接入约束。

## 1. 当前结论

- 旧 `workflow + resume_intervention + external_interaction_task` 模型已经退出主工程，并已从当前 Flyway schema 与 jOOQ generated surface 清理。
- 当前控制面公开的 runtime API 只有 `/api/session-runtime/...`。
- external interaction 不再作为独立 runtime 主模型存在，而是 session / playbook 流程中的一种等待点与事件来源。

## 2. 新架构定位

- session workflow 是唯一会话控制中心。
- playbook child workflow 负责强流程、等待点、恢复和终态。
- external interaction 属于 playbook 的 `WAITING` 原因之一，而不是独立 workflow/checkpoint 体系。
- 恢复结果应通过：
  - `playbook run` 状态变更
  - `session event`
  - `sharedState`
  进入主链路。

## 3. 对象边界

- `session`
  - 持有当前 owner、活跃 playbook、shared state、handoff 状态
- `session_event`
  - 记录 `EXTERNAL_CALLBACK_RECEIVED` 等运行时事实
- `playbook_run`
  - 记录当前等待原因、输入、结果、失败原因

这些旧对象已不再作为 schema/API/generated code surface 保留：

- `ExternalInteractionTask`
- `ExternalInteractionEvent`
- `ConversationMessage(payloadType = EXTERNAL_INTERACTION)`
- `workflow resume` / `resume_intervention`

## 4. 推荐接入方式

当 playbook 节点需要第三方站外交互时：

1. playbook workflow 进入 `WAITING`
2. 等待原因写入 `playbook_run.waitingReason`
3. 相关上下文写入 `sharedState`
4. 外部系统或人工恢复信号回到 session workflow
5. session workflow 追加 `session_event`
6. session workflow 触发 playbook resume 或 owner reevaluation

## 5. API 约束

- 当前未定义独立的 external interaction 公共 HTTP API。
- 如果后续需要新增回调或恢复入口，应直接挂到 session runtime 语义下，而不是重建旧 `/runtime/interactions` 或 `/workflows/{id}/resume` 接口族。
- 新接口必须围绕以下对象设计：
  - `sessionId`
  - `playbookRunId`
  - `session_event`
  - `playbook_run`

## 6. 落地要求

- 不允许重新引入旧 `external_interaction_task` 投影模型。
- 不允许让前端或 provider callback 直接驱动旧 workflow resume 语义。
- 所有新实现都必须与当前代码中的 `session / session_event / playbook_run` 模型一致。
