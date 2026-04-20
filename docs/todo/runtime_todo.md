# Runtime TODO

> 这份文档是 [`docs/project_todos.md`](../project_todos.md) §2.1 / §2.2 / §2.3 / §2.6 的详细补充，记录 `session-runtime` 新架构下仍然有效的实施细节。
> 凡是基于旧 `task / workflow instance / resume action / outputMessages` 主模型的事项，都不再保留。

各章节与主 todos 的对应关系：

- §1 SSE 推送 ↔ 主 todos §2.2
- §2 人工接管与恢复操作台 ↔ 主 todos §2.3
- §3 长 session 历史分页 ↔ 主 todos §2.6
- §4 审计账本与运行投影分层 ↔ 主 todos §2.1

## 1. 订阅式运行观测仍未落地

当前状态：

- 运行入口已收敛为 `/api/session-runtime/...`
- Web 运行页当前通过 `GET /api/session-runtime/sessions` 和 `GET /api/session-runtime/sessions/{sessionId}` 轮询刷新
- session 权威投影已经落到：
  - `session_runtime_session`
  - `session_runtime_event`
  - `session_runtime_playbook_run`

当前缺口：

1. 没有 `session` 级 SSE / WebSocket 推送
2. owner reply、playbook waiting / resumed / completed、handoff 状态变化不能即时推到前端
3. 当前轮询粒度是整份 detail，延迟和资源消耗都偏高

下一步：

1. 基于 `docs/todo/sse_plan.md` 补 `GET /api/session-runtime/sessions/{sessionId}/stream`
2. 先覆盖运行会话页，再评估是否扩展到全局 session 列表
3. 保持“完整快照推送”，避免重新发明 delta 协议

## 2. 人工接管与恢复操作台还不完整

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

## 3. 长 session 历史仍缺分页和派生视图

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

## 4. 审计账本与运行投影仍未分层

当前状态：

- `session_runtime_event` 已经是 append-only 时间线
- 但它当前仍承担运行查询主视图，而不是完整审计账本

当前缺口：

1. 没有统一的跨对象审计查询入口
2. 没有把 catalog 变更和 session runtime 事件放到统一 audit 模型下
3. 运维告警、失败统计和合规追踪仍主要依赖日志而不是事件查询

下一步：

1. 在全局事件日志方案里把 `session event / playbook run` 纳入统一审计边界
2. 为关键运行事件补 actor、来源、聚合键和查询接口
3. 明确“运行投影”和“审计事件”的保留策略与职责分工
