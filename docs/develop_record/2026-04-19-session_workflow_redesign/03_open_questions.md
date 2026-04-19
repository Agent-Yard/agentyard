# Session Workflow 重构待确认问题

## 已关闭问题

### Q1. Playbook 配置对象在 catalog 中的挂载层级

- 最终结论：
  - 已按 assistant 级私有对象落地
  - assistant release 会冻结 playbook 定义，agent 的 `playbookIds` 仅在所属 assistant 范围内引用
- 状态：
  - 已关闭

### Q2. Session 级串行锁的实现载体

- 最终结论：
  - 第一阶段已按 API 进程内显式锁落地 `sessionId / 对话绑定键` 串行化
  - 若未来扩为多实例，可升级为共享锁设施；不影响本次重构闭环
- 状态：
  - 已关闭

### Q3. 旧 `task` 产品概念是否完全退场

- 最终结论：
  - task 已完全退出 runtime 主路径，对外会话入口统一为 session
- 状态：
  - 已关闭

### Q4. knowledge-service 是否需要整体并入 runtime

- 最终结论：
  - 不整体并入
  - 保留统一 knowledge store 与独立 knowledge-service
  - 在线检索由 runtime 通过内部接口远程调用 knowledge-service 完成
  - 离线导入、切片、索引构建继续由 knowledge-service 负责
- 状态：
  - 已关闭

### Q5. `sharedState` 在 PromptRuntimeMessages 中的暴露形态

- 最终结论：
  - `sharedState` 统一按扁平 KV 处理
  - runtime 只允许在 prompt 组装阶段基于 memory window / 字节预算做最小化裁剪视图，不再恢复 `facts / artifacts / agentScope` 分区模型
- 状态：
  - 已关闭

## 当前状态

- 当前没有阻塞本次重构闭环的待确认问题；剩余项均属于已确定方向下的实现工作

## 若后续继续扩展时需重新提问的条件

- 若未来设计要求把 playbook 提升到 assistant 之外的共享层级，需要重新定义 catalog 归属模型
- 若 handoff 人工侧接口需要新增产品语义而设计文档未定义，需要重新确认
- 若 session 查询/列表语义与前端核心操作路径出现新的不可化解冲突，需要重新确认
