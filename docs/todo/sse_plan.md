# Session Runtime SSE 落地方案

## Summary

- 范围只覆盖运行会话页，即 [RuntimeConversationPage.vue](/Users/eric/projects/lynxus/apps/web/src/pages/RuntimeConversationPage.vue)。
- 目标是把当前“轮询 `session detail`”升级为“优先 SSE、失败降级轮询”。
- SSE 主模型必须对齐当前 session workflow 架构，只围绕 `session / session event / playbook run` 推送，不重新引入旧 `task / workflow instance` 语义。
- 说明：截至 2026-04-20，平台基础设施已完成共享 Redis 接入；但本方案这一版仍不实现基于 Redis 的 SSE 跨实例广播，相关能力继续归属 `docs/project_todos.md` §3.7。

## Public APIs / Interfaces

- 新增 `GET /api/session-runtime/sessions/{sessionId}/stream`，返回 `text/event-stream`
- 新增共享事件契约 `SessionRuntimeStreamEvent`，建议放在 [packages/contracts/src/index.ts](/Users/eric/projects/lynxus/packages/contracts/src/index.ts)
- 建议契约：
  - `id: string`
  - `type: 'SESSION_SNAPSHOT' | 'SESSION_UPDATED'`
  - `occurredAt: string`
  - `sessionId: string`
  - `detail: SessionRuntimeDetail`
- SSE 语义：
  - 首次连接先发一条完整 `SESSION_SNAPSHOT`
  - 重连时若 `Last-Event-ID` 命中缓存，则 replay 未消费事件
  - 缓存 miss 时退回最新 `SESSION_SNAPSHOT`
  - 心跳使用 SSE comment，不占业务 event type

## Event Source Strategy

当前 worker 通过持久化 activity 直接写数据库，API 不是唯一写入口，所以不能只在 controller/service 写路径上发 SSE。

建议实现：

1. API 维护 `sessionId -> emitters` 注册表和有限 replay buffer
2. API 增加 session stream watcher，轮询关注中的 `session_runtime_session.updated_at / latest_event_sequence`
3. 发现变化后重新加载 `SessionRuntimeDetail` 并广播一条 `SESSION_UPDATED`

这样做的原因：

- 不需要修改 worker 的持久化边界
- 能直接复用现有 repository 和 detail DTO
- 保持 session workflow 是运行事实来源，SSE 只是 detail 的订阅分发层

## Implementation Changes

- API 侧新增 `SessionRuntimeStreamService`
  - 管理连接
  - 管理 replay buffer
  - 管理 watcher 与事件广播
- `JdbcSessionRuntimeRepository` 增加轻量查询：
  - 按 `sessionId` 读取 `updatedAt / latestEventSequence`
  - 批量查询活跃 session 的最新摘要
- Controller 新增 SSE endpoint，鉴权沿用 `@RequireRuntimeAccess`
- Web 侧新增 runtime stream composable：
  - 仅在运行页且存在 `selectedSessionId` 时启用
  - SSE 正常时停止 detail 轮询
  - SSE 失败时回退到现有轮询

## Frontend Merge Strategy

前端仍采用“完整 detail 覆盖”而不是 patch 合并：

1. 用 `detail.session` 覆盖当前 `sessions` 对应项
2. 若事件来自当前选中的 session，直接用 `detail` 覆盖本地 `sessionDetail`
3. 不为 `session event` 和 `playbook run` 设计额外增量协议

这样做的原因：

- 当前 detail 结构已经稳定
- session 事件顺序和 playbook run 状态由后端权威生成
- 完整覆盖更容易保证 owner、handoff、pending reevaluation 等组合状态一致

## Test Plan

- API 单测：
  - SSE endpoint 返回 `text/event-stream`
  - 首次连接立即收到 `SESSION_SNAPSHOT`
  - 带 `Last-Event-ID` 重连时可 replay
  - 连接断开后 emitter 能正确清理
- Repository / service 单测：
  - session `latest_event_sequence` 变化时触发广播
  - playbook run 状态变化会导致 detail 更新并推送
  - handoff 开始/结束和 human reply 也能推送
- Web 单测：
  - 收到事件后能正确覆盖 `sessionDetail`
  - SSE `error` 后退回轮询
  - 重新进入运行页时会尝试重连

## Non-goals

- 不覆盖全局 session 列表实时化
- 不做细粒度 delta 协议
- 不把 SSE 扩展到 catalog 页面
- 不在本期实现基于 Redis / MQ / 持久事件总线的 SSE 跨实例广播
