# External Interaction 使用说明

这份文档描述当前 Lynxus 已落地的 external interaction 通用框架如何使用，以及后续真实 provider 应如何对接。

适用范围：

- 控制面或运行态编排逻辑需要创建一个站外交互任务
- 前端需要处理第三方回跳
- 后端需要接收 provider callback
- assistant / workflow 需要在恢复后基于回跳或 callback 结果继续执行

## 1. 当前协议

当前 external interaction 采用以下统一约束：

- `ExternalInteractionTask` 是唯一真相源
- `ConversationMessage(payloadType = EXTERNAL_INTERACTION)` 只是 task 的展示投影
- runtime 出站统一走 `WorkflowResult.outputMessages`
- `EXTERNAL_INTERACTION` 只是其中一种 output message；assistant 文本输出也走同一条出口
- 前端回跳不是可信结果信源，只表示“用户已经返回”
- 首次有效 `FRONTEND_RETURN` 会把 task 推进到 `RETURNED` 并立即恢复 workflow
- 首次有效 `PROVIDER_CALLBACK` 会把 task 推进到 `PROCESSING`，如带可信结果则写入 `latestResult`，并立即恢复 workflow
- 恢复后是否调用工具确认真实结果，属于 assistant / workflow 配置逻辑，不属于系统硬编码
- 同一 task 的重复 return / callback 只记事件，不重复恢复 workflow

## 2. 数据模型

核心对象：

- `ExternalInteractionTask`
  - 归属：`sessionId / taskId / workflowInstanceId / messageId`
  - 协议：`type / status / provider / providerReference / launchUrl / returnToken / returnPath / expiresAt`
  - 展示：`title / instruction`
  - 运行态：`latestResult / lastEventSource / resumedAt`
- `ExternalInteractionEvent`
  - 审计和幂等载体
  - 当前事件源：`SYSTEM_CREATE / FRONTEND_RETURN / PROVIDER_CALLBACK`
  - 当前事件类型：`CREATED / RETURNED / CALLBACK_RECEIVED / RESUME_TRIGGERED / IGNORED`

消息卡片 payload：

- `spec`
  - `interactionType / title / instruction / provider / providerReference / launchUrl / returnPath / expiresAt`
  - `primaryActionLabel / secondaryActions / displayHints`
- `projection`
  - `interactionTaskId / status / primaryAction / secondaryActions / displayHints`

统一 output message 结构：

- `messageKey`
- `payloadType`
- `payload`
- `createdAt`

## 3. API 一览

当前 API：

- `GET /api/runtime/interactions/{taskId}`
- `POST /api/runtime/interactions/{taskId}/return`
- `POST /api/runtime/interactions/callbacks/{provider}`

补充说明：

- interaction 创建不是公共 HTTP API
- 当前设计中，interaction 应由控制面 API 内部在运行态编排过程中创建
- worker 不负责直接写控制面投影或持久化 interaction task

### 3.1 runtime 如何创建 interaction task

用途：

- agent-runtime 发出 `EXTERNAL_INTERACTION` output message 后，由控制面 API 在投影阶段创建 interaction task 和消息卡片

入口边界：

- 这是控制面 API 内部能力，不是对外开放接口
- 推荐职责边界是：agent-runtime / worker 产出结构化 `WorkflowResult.outputMessages`，API 负责真正创建 `ExternalInteractionTask`、消息投影和事件记录
- 不推荐让 worker 直接写数据库，因为 task、message、event、checkpoint 对齐都属于控制面投影责任

agent-runtime output message 示例：

```json
{
  "messageKey": "external-interaction-card",
  "payloadType": "EXTERNAL_INTERACTION",
  "createdAt": "2026-04-01T12:00:00Z",
  "payload": {
    "spec": {
      "interactionType": "OAUTH_REDIRECT",
      "title": "完成第三方授权",
      "instruction": "请前往第三方页面完成授权后返回。",
      "provider": "oauth-demo",
      "providerReference": "oauth-order-1",
      "launchUrl": "https://provider.example.com/oauth/start?state=abc",
      "returnPath": "/console/runtime",
      "expiresAt": null,
      "primaryActionLabel": "去授权",
      "secondaryActions": [],
      "displayHints": {
        "intent": "oauth"
      }
    }
  }
}
```

创建成功后系统会同时完成：

- 写入 `external_interaction_task`
- 写入 `external_interaction_event(type=CREATED)`
- 向会话追加一条带 `messageKey` 的 `EXTERNAL_INTERACTION` assistant 消息
- 把 workflow checkpoint 的 `resumeContext.interactionTaskId / interactionType` 对齐到该 task

约束：

- `WorkflowResult.outputMessages` 是 workflow 级累计有序列表，不是单轮 delta
- `messageKey` 在单 workflow 内唯一且不可变
- API 依据 `(workflow_instance_id, messageKey)` 幂等投影消息

### 3.2 查询 interaction task

用途：

- 前端展示交互卡片详情
- 回跳后刷新当前状态
- 调试 callback / 幂等问题

响应里会返回：

- task 主状态
- 最近结果 `latestResult`
- 事件列表 `events`

### 3.3 前端回跳 ack

接口：

- `POST /api/runtime/interactions/{taskId}/return`

用途：

- 第三方把用户重定向回控制台后，由前端确认“用户已返回”

请求示例：

```json
{
  "returnToken": "return-abc",
  "providerReference": "oauth-order-1",
  "dedupeKey": "frontend-return:session-123:1",
  "payload": {
    "code": "temp-code",
    "state": "abc"
  }
}
```

语义：

- 校验 `returnToken`
- 首次有效事件把 task 推进到 `RETURNED`
- 立即触发 `EXTERNAL_SYSTEM` resume
- 重复 `dedupeKey` 不会重复恢复

### 3.4 后端 callback ingest

接口：

- `POST /api/runtime/interactions/callbacks/{provider}`

用途：

- provider 服务端回调进入控制面

请求示例：

```json
{
  "taskId": "interaction-123",
  "providerReference": "oauth-order-1",
  "dedupeKey": "provider-callback:event-1",
  "payload": {
    "providerStatus": "APPROVED",
    "raw": {
      "eventId": "evt-1"
    }
  },
  "result": {
    "outcome": "SUCCEEDED",
    "code": "APPROVED",
    "summary": "授权成功",
    "rawProviderStatus": "APPROVED",
    "attributes": {
      "authorizationId": "auth-1"
    }
  }
}
```

语义：

- 优先按 `taskId` 命中 task
- 如果没有 `taskId`，允许按 `provider + providerReference` 命中 task
- 首次有效事件把 task 推进到 `PROCESSING`
- 如果请求带 `result`，则写入 `latestResult`
- 立即触发 `EXTERNAL_SYSTEM` resume
- 重复 `dedupeKey` 不会重复恢复

## 4. 前端接入说明

当前前端约定使用 query 参数处理回跳。

支持的回跳参数：

- `interactionTaskId`
- `returnToken`
- `providerReference`
- `interactionDedupeKey`

当前控制台壳层行为：

1. 进入 `/console/...` 路由时解析 query
2. 若同时存在 `interactionTaskId + returnToken`，自动调用 return ack
3. 刷新 session / workflow
4. 把当前页面切回 runtime 观察页

建议 provider return URL 至少带上：

```text
/console/runtime?interactionTaskId=...&returnToken=...&providerReference=...
```

如果 provider 支持唯一事件号，建议同时带：

```text
interactionDedupeKey=...
```

## 5. Assistant / Workflow 恢复后可见信息

系统恢复 workflow 时，会把 external interaction 相关信息写入 `ResumeAction.attributes`。

当前字段：

- `interactionTaskId`
- `callbackSource`
  - `FRONTEND_RETURN`
  - `PROVIDER_CALLBACK`
- `providerReference`
- `resultPayload`
  - 仅 callback 携带可信结果时存在
- `eventPayload`
  - 当前 return / callback 的原始输入摘要

这意味着恢复后的 assistant / workflow 可以：

- 根据 `callbackSource` 判断这次恢复来自前端 return 还是 provider callback
- 如果只有前端 return，则自行决定是否调用工具确认真实结果
- 如果 callback 已携带可信结果，则直接消费 `resultPayload`

## 6. 推荐使用方式

### 6.1 通用 redirect 交互

适合：

- OAuth 授权
- 外部表单填写
- 外部确认页

推荐做法：

1. workflow 先进入 `WAITING_RESUME`
2. 控制面创建 `ExternalInteractionTask`
3. 卡片主按钮跳转外部页面
4. 用户回跳后由前端 ack
5. 恢复后的 assistant 根据 `callbackSource` 决定是否查状态

### 6.2 callback 可直接给出结果的交互

适合：

- 某些支付平台
- 某些 OAuth / 签约平台
- 内部系统异步回调

推荐做法：

1. 创建 interaction task
2. provider callback 直接调用 callback ingest
3. 在 callback 中放入标准化 `result`
4. workflow 恢复后直接消费 `resultPayload`

## 7. Provider 对接要求

当前第二步实现前，provider 至少应满足：

- 能提供稳定的 `providerReference`
- 尽量提供幂等事件号，用于 `dedupeKey`
- 能在浏览器回跳时带回 `interactionTaskId / returnToken`
- 最好支持服务端 callback

后续真实 provider 接入时还需要补：

- 签名校验
- adapter 抽象
- 主动查单 / 查状态补偿
- provider 特化字段映射

## 8. 当前限制

当前框架已可用于通用交互打通，但仍有限制：

- 还没有 provider adapter，callback payload 仍是通用 JSON
- 还没有系统级“launch”状态回写，点击主按钮不会自动把 task 标记为 `LAUNCHED`
- 还没有签名校验
- 还没有查单补偿
- 轮询仍基于前端定时刷新，不是 SSE

## 9. 实施建议

如果要接入第一个真实 provider，建议按下面顺序推进：

1. 固定 provider 的 `providerReference` 和 `dedupeKey` 策略
2. 固定 return URL 参数格式
3. 固定 callback body 到 `ExternalInteractionCallbackRequest` 的映射
4. 在 assistant / workflow 中定义恢复后如何消费 `callbackSource / resultPayload`
5. 最后再补签名校验和查单补偿
