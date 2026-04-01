# 2.2 运行态 Session SSE 推送落地方案

## Summary
- 以“仅会话页”为本次 2.2 范围：补齐 session 级 SSE，把 [RuntimeConversationPage.vue](/Users/eric/projects/lynxus/apps/web/src/pages/RuntimeConversationPage.vue) 从全局 3 秒轮询切到“优先 SSE、失败降级轮询”；[WorkflowPage.vue](/Users/eric/projects/lynxus/apps/web/src/pages/WorkflowPage.vue) 继续保留现有轮询。
- 后端不做每连接独立轮询，改为 API 侧集中 reconcile 活跃 workflow，并把 session 最新快照发布到 SSE 订阅者；这样复用现有投影模型，符合仓库的整体设计要求。
- SSE 推送不发细粒度 patch，统一发“完整运行态快照事件”，前端直接合并 `session/task/workflow`，避免在现有投影模型上再发明一套 delta 协议。

## Public APIs / Interfaces
- 新增 `GET /api/runtime/sessions/{sessionId}/stream`，返回 `text/event-stream`。
- 新增共享事件契约 `RuntimeSessionStreamEvent`，放在 [packages/contracts/src/index.ts](/Users/eric/projects/lynxus/packages/contracts/src/index.ts) 并同步 OpenAPI：
  - `id: string`
  - `type: 'SESSION_SNAPSHOT' | 'SESSION_MESSAGE' | 'WORKFLOW_UPDATED' | 'HUMAN_TASK_UPDATED'`
  - `occurredAt: string`
  - `session: ConversationSession`
  - `task: TaskInstance | null`
  - `workflow: WorkflowInstance | null`
- SSE 语义固定：
  - 首次连接无 `Last-Event-ID` 时先发 `SESSION_SNAPSHOT`
  - 带 `Last-Event-ID` 且缓存命中时，按序 replay 未消费事件
  - 缓存未命中时回退为一条最新 `SESSION_SNAPSHOT`
  - 心跳使用 SSE comment，不占业务 event type
- 新增运行态流配置，默认值直接定死到实现中并允许配置覆盖：
  - reconcile 间隔 `1000ms`
  - heartbeat 间隔 `15s`
  - 每 session replay buffer `50` 条

## Implementation Changes
- API 侧新增 session stream 服务，职责固定为三件事：维护 `sessionId -> emitters` 注册表、维护按 session 分组的有限 replay buffer、把运行态快照发布成 SSE event。
- [apps/api/src/main/java/com/lynxus/platform/runtime](/Users/eric/projects/lynxus/apps/api/src/main/java/com/lynxus/platform/runtime) 内把“投影更新”与“事件发布”收口到同一条路径：
  - `createSession` / `sendMessage` 在 session 初始投影落库后发布 `SESSION_SNAPSHOT` 或 `SESSION_MESSAGE`
  - `handleHumanAction` 在挂起态转“恢复处理中”后发布 `WORKFLOW_UPDATED`
  - `refreshRunningWorkflows()` / `reconcilePendingHumanInterventions()` 在发现投影变化并持久化后发布 `WORKFLOW_UPDATED`；若 `latestHumanTask` 或 `latestPauseReason` 发生变化，则事件类型为 `HUMAN_TASK_UPDATED`
- 为了让 SSE 在“无人主动读接口”时仍能收到更新，新增 API 侧定时 reconcile 组件，周期调用现有 `reconcileRunningWorkflows()`；启动时保留现有 `RuntimeProjectionReconciler` 首次对账。
- Controller 新增 SSE endpoint，鉴权沿用 `@RequireRuntimeAccess`；连接建立时立即读取当前 session / latest task / latest workflow 组装初始快照。
- Web 侧新增 session stream composable，绑定条件固定为：
  - `activeKey === 'runtime'`
  - 存在 `selectedSessionId`
  - 当前浏览器支持 `EventSource`
- 前端状态更新策略固定为“流式合并，不做全量 refresh”：
  - 用事件中的 `session` 替换 `conversationSessions` 对应项
  - 用 `task`、`workflow` 覆盖 `tasks` / `workflows` 中同 id 项；不存在则插入
  - 若当前选中 workflow 正好是该 session 最新 workflow，则同步刷新详情显示
- 全局 polling 改为按页面启用：
  - `WorkflowPage` 保留现有 3 秒轮询
  - `RuntimeConversationPage` 在 SSE 正常时禁用全局 runtime 轮询
  - SSE 连接失败、浏览器不支持或连续出错时，runtime 页回退到现有 `refresh()` 轮询，并在下次切换 session 或页面重进时重试 SSE
- 文档同步更新，但不动 `docs/develop_record/`：
  - 在 [docs/project_todos.md](/Users/eric/projects/lynxus/docs/project_todos.md) 把 2.2 改为已完成状态，并注明本期范围仅覆盖会话页
  - 在 `docs/todo/runtime_todo.md` 删除“SSE / WebSocket 待补”项，改为说明“会话页已 SSE，workflow 观测页仍轮询”

## Test Plan
- API 单测：
  - SSE endpoint 返回 `text/event-stream`，首次连接能拿到 `SESSION_SNAPSHOT`
  - 带 `Last-Event-ID` 重连时能 replay 后续事件；缓存 miss 时退回最新 snapshot
  - emitter 断开、超时、异常后能正确清理注册表
- RuntimeService 单测：
  - `sendMessage` 发布消息事件
  - workflow 从 `RUNNING -> COMPLETED / WAITING_HUMAN / FAILED` 时只在投影变化时发布一次事件
  - pending human intervention 被 apply 后，session 消息与 workflow 状态同步更新并推送
- Web 单测：
  - stream composable 收到事件后正确合并 `conversationSessions / tasks / workflows`
  - SSE `error` 后进入 polling fallback，再次进入 runtime 页时会重试连接
  - `WorkflowPage` 仍保持原 polling，不受 runtime SSE 影响
- 手工验收：
  - 创建会话后占位消息立刻出现，后续 assistant 回复无需手动刷新
  - workflow 进入 `WAITING_HUMAN` 时，会话页告警自动出现
  - 人工恢复后，会话页自动从“待人工”切回“运行中/已完成”
  - 断网或关闭 API 后回退轮询；API 恢复后刷新页面可重新进入 SSE

## Assumptions
- 本次 2.2 明确不覆盖全量 workflow 列表实时化；那属于更大范围的 runtime 全局 stream，不并入本次实现。
- 当前是开发中系统，允许 SSE replay buffer 先采用 API 进程内内存实现，不引入 Redis / MQ / 持久事件总线。
- 前端默认通过同域 `/api` 或 Vite proxy 访问 API；若未来启用跨域 `VITE_API_BASE_URL`，实现时一并带上 `EventSource` 的凭据配置，并补齐对应 CORS credentials 设置。
