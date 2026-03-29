# 会话级共享状态重构方案（facts / artifacts / agentScopes）

## Summary
将当前只能在单次 workflow 内生效的 `agent_runtime state`，重构为“运行态”和“会话共享态”两层：

- 运行态继续只服务单次 workflow / checkpoint 恢复，不跨下一条消息复用。
- 新增显式 `sharedState` 作为 session 级共享状态，固定只有三类 bucket：`facts`、`artifacts`、`agentScopes`。
- 不做持久化；本阶段仍以 API 进程内存中的 session 为真源。
- 不做兼容；直接升级 runtime contract、API DTO、TS/OpenAPI、测试。

`loadedSkillResourceVersionIds`、`checkpoint`、`tool_history`、`agent_turn_state` 这类运行控制/观测字段不并入 `sharedState`，继续保持独立，避免把“可恢复执行态”和“可跨轮次复用态”混在一起。

## Key Changes

### 1. 共享状态模型与契约
新增 `SharedSessionState`，结构固定、key 完全业务无关：

```json
{
  "facts": {},
  "artifacts": {},
  "agentScopes": {
    "agent-id": {}
  }
}
```

新增到以下契约中：
- `SessionContext.sharedState`
- `WorkflowResult.sharedState`
- `ConversationSessionDto.sharedState`
- `WorkflowInstanceDto.sharedState`

约束：
- `facts`：全局共享、结构化业务事实，适合稳定真值。
- `artifacts`：全局共享、工具产物或中间结论摘要。
- `agentScopes`：按 `agentId` 分区的私有 scratchpad。
- 不预置任何业务字段名，不保留 `orderId` 一类硬编码 schema。
- 值仅允许 JSON 兼容类型；数组允许作为整体 value，但不支持数组路径级 patch。

### 2. patch 协议与 merge 语义
agent 到 runtime 的写入协议采用操作列表，不允许整桶覆盖：

```json
{
  "sessionStatePatch": {
    "ops": [
      {
        "target": "FACTS | ARTIFACTS | AGENT_SCOPE",
        "op": "UPSERT | REMOVE",
        "path": ["segment1", "segment2"],
        "value": {}
      }
    ]
  }
}
```

规则：
- `path` 必填、非空、每段非空字符串。
- `value` 仅在 `UPSERT` 时必填；`REMOVE` 时忽略。
- `AGENT_SCOPE` 不允许指定别的 agent，永远隐式指向当前执行节点的 `agentId`。
- `UPSERT` 语义：按对象路径写入；缺失中间对象自动创建；若中间节点已存在但不是 object，则判定 patch 非法。
- `REMOVE` 语义：按路径删除；路径不存在时按 no-op 处理。
- 同一响应内多个 op 按顺序执行；后一个 op 覆盖前一个 op 的结果。
- runtime 对 patch 做完整校验，非法时走现有 `MODEL_OUTPUT_INVALID` / 人工介入兜底链路，不静默容错。

### 3. agent 可见性与提示词注入
prompt 中新增共享状态上下文，但严格限制可见范围：

- 所有 agent 都能看到：`facts`、`artifacts`
- 当前 agent 只能看到自己的：`agentScope`
- 不向 agent 暴露其他 agent 的 scope
- `loadedSkillResourceVersionIds` 继续走原有 skill 机制，不混入共享状态 prompt

`build_structured_agent_prompt` 升级：
- 输入新增 `sharedFacts`、`sharedArtifacts`、`agentScope`
- 输出 schema 新增可选 `sessionStatePatch`
- 允许任意 `decisionType` 携带 patch：`FINAL`、`TOOL_CALL`、`SKILL_READ`、`HUMAN_HANDOFF` 都可写
- patch 在当前轮 decision 校验通过后立即应用到 runtime 内存态
- `TOOL_CALL` 不自动把工具结果写入 `artifacts`；只有模型后续显式输出 patch 才会落共享状态

### 4. runtime / API 数据流
运行时内部分层明确化：

- `AgentState` 保留单次 workflow 执行字段：节点、路由、tool history、checkpoint、human input、turn state 等
- `session_context.sharedState` 成为唯一跨消息共享入口
- `export_state/restore_state` 在 checkpoint 中继续携带 workflow 运行态；resume 时仍以请求里的 `sessionContext.sharedState` 为准覆盖恢复态里的 session 视图

消息链路：
1. `RuntimeService.sendMessage` 从 `ConversationSessionDto.sharedState` 构造 `SessionContext.sharedState`
2. `agent-runtime start/resume` 运行后返回规范化后的 `WorkflowResult.sharedState`
3. `RuntimeService` 直接用返回的 `sharedState` 更新 `WorkflowInstanceDto` 和 `ConversationSessionDto`
4. 下一条消息再次启动时，新的 `sharedState` 自动回灌到 runtime

这意味着 session 级共享态的唯一归并点在 runtime，Java 侧不重复实现 patch merge。

### 5. 校验、限制与观测
为了避免共享态失控，v1 增加硬约束：

- 仅允许对象路径 patch，不支持数组索引路径
- 单个 `UPSERT.value` 和整体 `sharedState` 需做序列化大小限制；超限按 patch 非法处理
- `WorkflowResult`、session 详情、workflow 详情都返回 `sharedState` 快照，便于调试
- 运行页只做只读 JSON 观测，不做编辑能力

## Test Plan

### Python runtime
- patch 解析与校验：合法 `UPSERT/REMOVE`、非法 path、非法 target、非法中间节点类型
- prompt 可见性：agent 只能看到 `facts/artifacts + 自己的 agentScope`
- patch 应用时机：`FINAL`、`TOOL_CALL`、`SKILL_READ`、`HUMAN_HANDOFF` 四类 decision 都能正确更新 `sharedState`
- checkpoint / resume：暂停恢复后共享态不丢失，且以 `resume request.sessionContext.sharedState` 为当前真值
- 工具结果默认不落 `artifacts`

### Java API / RuntimeService
- 同一 session 连续两条消息：第一条写入 `facts/artifacts/agentScopes`，第二条启动时能带回 runtime
- human action 恢复后：workflow/session 的 `sharedState` 同步更新
- workflow 刷新路径：`currentResult()` 返回新 `sharedState` 时 session 也同步刷新
- 无 session 的 adhoc task：允许携带空 `sharedState`

### Contract / Web
- JVM contracts、OpenAPI、TS types 同步包含 `sharedState`
- 会话页 / workflow 页能只读展示返回的 `sharedState` 快照

## Assumptions
- 本阶段不做 DB 持久化；API 重启后 session 共享态会丢失，这是显式接受的限制。
- 本阶段不做旧 contract 兼容；直接更新 [`/Users/eric/projects/lynxus/packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`](/Users/eric/projects/lynxus/packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java)、[`/Users/eric/projects/lynxus/apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java`](/Users/eric/projects/lynxus/apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java)、[`/Users/eric/projects/lynxus/apps/agent-runtime/app/main.py`](/Users/eric/projects/lynxus/apps/agent-runtime/app/main.py) 及对应测试。
- session 共享态的 bucket 固定为三类，但 bucket 内部 key/path 完全开放，不预定义业务字段。
- `agentScopes` 的读写权限固定为“仅自己可读写”；不支持跨 agent 指定访问。
