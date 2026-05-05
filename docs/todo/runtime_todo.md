# Runtime TODO

> 这份文档是 [`docs/project_todos.md`](../project_todos.md) §2.1 / §2.4 的详细补充，记录 `session-runtime` 新架构下仍然有效的实施细节。
> 凡是基于旧 `task / workflow instance / resume action / outputMessages` 主模型的事项，都不再保留。
> 已完成的条目（§1 SSE 订阅式观测、§4 审计账本与运行投影分层）不再罗列，详见主 todos 末尾的「已完成工作回顾」。

各章节与主 todos 的对应关系：

- §1 人工接管与恢复操作台 ↔ 主 todos §2.1
- §2 长 session 历史分页 ↔ 主 todos §2.4
- 历史流式执行与回复草稿方案已归档至 [`runtime_streaming_plan.md`](../develop_record/runtime_streaming_plan.md)
- channel outbound frame stream 改造方案见 [`channel_outbound_frame_stream_plan.md`](../develop_record/channel_outbound_frame_stream_plan.md)

## 1. 人工接管与恢复操作台还不完整

当前状态：

- API 已支持：
  - `POST /api/session-runtime/sessions/{sessionId}/human-reply`
  - `POST /api/session-runtime/sessions/{sessionId}/human-resume`
  - `POST /api/session-runtime/sessions/{sessionId}/external-callback`
  - `POST /api/session-runtime/sessions/{sessionId}/handoff/end`
- Worker 已支持：
  - handoff 期间写 `HUMAN_OPERATOR_REPLY`
  - playbook 等待态的人工作业恢复
  - 外部回调恢复

当前缺口：

1. Web 运行页还只有会话创建、消息发送、事件查看和 playbook run 查看
2. 没有针对 handoff、human resume、external callback 的内置操作面板
3. 人工操作与事件时间线之间缺少针对性的视图收口

下一步：

1. 在运行页补人工回复、结束 handoff、恢复 waiting playbook 的表单
2. 对 `PLAYBOOK_WAITING` / `SESSION_HUMAN_HANDOFF_STARTED` 增加明确操作提示
3. 区分“业务用户发送消息”和“人工操作员处理会话”两类入口

## 2. 长 session 历史仍缺分页和派生视图

当前状态：

- `GET /api/session-runtime/sessions/{sessionId}` 直接返回完整 `events + playbookRuns`
- 运行页直接展示完整时间线和全部 playbook run

当前缺口：

1. session 历史增长后，整量返回 detail 会越来越重
2. 当前没有按 event type、owner、playbook run 的过滤与聚合能力
3. 排障时仍需人工在完整事件流里找等待点、拒绝决策和失败回合

下一步：

1. 为 session event 增加分页、过滤和按类型聚合查询
2. 为 playbook run 增加按状态、waiting reason 的快捷筛选
3. 在 UI 中补“仅看用户可见消息 / 仅看系统事件 / 仅看 playbook 事件”视图
