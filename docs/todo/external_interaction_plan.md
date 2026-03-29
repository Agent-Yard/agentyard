# External Interaction 设计方案

这份文档用于规划 Lynxus 运行态中的通用 external interaction 能力。

这里的 external interaction 指：

- workflow 在运行过程中需要用户或外部系统完成一个站外动作
- 动作不一定是人工审批，也不一定只是支付
- 常见形态包括支付跳转、外部表单、OAuth 授权、第三方门户确认、线下动作回填

这份文档不只记录待办，还给出当前项目语境下的目标模型和分阶段落地方式。

## 1. 目标

希望把当前“对话里出现一条文本提示，用户自行跳转处理”的局部能力，升级为统一的运行态能力：

1. assistant / workflow 能声明“当前需要一个 external interaction”
2. 会话消息能以结构化卡片的形式承载这个动作
3. 前端能发起跳转、感知回跳、恢复上下文
4. 后端能基于 webhook 或主动查询确认结果
5. workflow 能在外部动作完成后继续推进
6. 会话中能自动追加下一步反馈，而不是依赖用户再次提问

## 2. 当前代码基线

当前项目已经具备一部分可复用基础，但还没有真正的 external interaction 抽象。

### 2.1 已有能力

- `RuntimeService` 已维护 `ConversationSession / TaskInstance / WorkflowInstance` 三类运行态对象
- workflow 已支持 `WAITING_HUMAN` 并通过 `handleHumanAction(...)` 恢复
- 前端 `RuntimeConversationPage.vue` 已能展示会话消息
- 前端 `WorkflowPage.vue` 已能展示挂起中的 workflow 并触发恢复动作
- `App.vue` 已有统一 `refresh()` 和 3 秒轮询机制，可作为 MVP 的状态传播通道

### 2.2 当前约束

- 会话消息目前只有 `content` 文本，没有结构化动作载荷
- runtime 数据目前主要在 API 进程内存中，跨支付跳转、进程重启、异步 webhook 的可靠性不足
- web 端没有正式路由，页面切换由 `App.vue` 的 `activeKey` 管理
- workflow 只建模了“人工恢复”，还没有“外部结果恢复”的通用契约

### 2.3 当前代码位置

- runtime DTO: `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeDtos.java`
- runtime service: `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java`
- workflow contracts: `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`
- workflow impl: `apps/worker/src/main/java/com/lynxus/worker/workflow/AssistantRunWorkflowImpl.java`
- runtime page: `apps/web/src/pages/RuntimeConversationPage.vue`
- workflow page: `apps/web/src/pages/WorkflowPage.vue`
- web app shell: `apps/web/src/App.vue`

## 3. 设计原则

### 3.1 统一建模，不为支付单独造旁路

支付只是 external interaction 的一种类型。
如果只为支付加字段，后续 OAuth、外部审批、签约确认、材料补录都会继续打补丁。

### 3.2 会话负责展示，workflow 负责状态推进

- 会话消息只负责呈现给用户看什么、点什么
- workflow 负责决定为什么要等、什么时候恢复、恢复后往哪里走

### 3.3 回跳不代表成功

前端回到应用只代表“用户已返回”。
最终结果必须以后端确认结果为准，来源可以是：

- 第三方 webhook
- 后端主动查单 / 查状态
- 内部系统回调

### 3.4 先复用现有轮询链路，再考虑推送

当前 `App.vue` 已有统一轮询刷新。
MVP 阶段优先复用轮询链路，不强依赖 SSE / WebSocket。

### 3.5 external interaction 必须持久化

这类能力天然跨跳转、跨请求、跨时间窗口。
只保留在 `RuntimeService` 内存中不够，至少 interaction task 必须持久化。

## 4. 统一对象模型

建议新增四类核心对象。

### 4.1 ExternalInteractionTask

表示一次可追踪的外部交互任务。

建议字段：

| 字段 | 含义 |
| -- | -- |
| `id` | interaction task id |
| `type` | 交互类型，如 `PAYMENT_REDIRECT` |
| `status` | 当前状态 |
| `sessionId` | 所属会话 |
| `taskId` | 所属任务实例 |
| `workflowInstanceId` | 所属 workflow |
| `messageId` | 关联的会话消息 |
| `title` | 对用户展示的标题 |
| `instruction` | 提示文案 |
| `provider` | 外部平台标识，如支付平台名 |
| `providerReference` | 外部平台侧业务单号 |
| `launchUrl` | 用户点击后要打开的链接 |
| `returnToken` | 回跳识别和防篡改 token |
| `returnMode` | `APP_RESUME` / `WEB_REDIRECT` |
| `returnPath` | 应用内恢复目标 |
| `expiresAt` | 过期时间 |
| `resultPayload` | 外部结果原始载荷摘要 |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |

### 4.2 InteractionCardPayload

挂在 `ConversationMessage` 上的结构化消息载荷，用于前端渲染。

建议字段：

| 字段 | 含义 |
| -- | -- |
| `kind` | 固定为 `EXTERNAL_INTERACTION` |
| `interactionTaskId` | 关联 interaction task |
| `interactionType` | 类型 |
| `title` | 卡片标题 |
| `description` | 卡片描述 |
| `status` | 卡片状态 |
| `primaryAction` | 主按钮 |
| `secondaryActions` | 次按钮 |
| `displayHints` | UI 渲染提示 |

### 4.3 ExternalInteractionResult

表示后端确认后的标准化结果。

建议字段：

| 字段 | 含义 |
| -- | -- |
| `outcome` | `SUCCEEDED / FAILED / CANCELLED / EXPIRED / PROCESSING` |
| `code` | 业务错误码或平台状态码 |
| `summary` | 结果摘要 |
| `rawProviderStatus` | 第三方原始状态 |
| `attributes` | 扩展属性 |

### 4.4 Workflow 外部等待上下文

`WorkflowResult` / `PauseReasonSnapshot` / `checkpoint.statePayload` 需要补充 external interaction 相关上下文，而不是只表达人工等待。

建议新增标准语义：

- `pauseReason.code = EXTERNAL_INTERACTION_REQUIRED`
- `pauseReason.source = EXTERNAL_INTERACTION`
- `statePayload` 中保存 `interactionTaskId`、`interactionType`、`resumePolicy`

## 5. 状态机

### 5.1 Interaction Task 状态

建议统一状态机如下：

`CREATED -> AWAITING_USER_ACTION -> LAUNCHED -> RETURNED -> PROCESSING -> SUCCEEDED`

异常终态：

- `FAILED`
- `CANCELLED`
- `EXPIRED`

说明：

- `CREATED`: 后端已创建，但会话消息还未完全发出
- `AWAITING_USER_ACTION`: 用户尚未点击
- `LAUNCHED`: 已触发站外动作
- `RETURNED`: 用户已回到应用，但还未最终确认结果
- `PROCESSING`: 后端已收到回调或正在查状态
- `SUCCEEDED / FAILED / CANCELLED / EXPIRED`: 最终态

### 5.2 Workflow 状态

workflow 不需要为每种外部动作单独发明状态。
建议继续沿用主状态：

- `RUNNING`
- `WAITING_HUMAN` 或后续扩展为 `WAITING_EXTERNAL`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

如果短期内不改主枚举，建议先通过：

- `status = WAITING_HUMAN`
- `pauseReason.code = EXTERNAL_INTERACTION_REQUIRED`

来表达“不是人工审批，而是在等待站外动作完成”。

后续如果 external interaction 明显增多，再考虑把 `WAITING_HUMAN` 泛化为 `WAITING_EXTERNAL`。

## 6. 交互类型设计

建议先定义通用枚举，而不是只定义支付。

首批可支持：

- `PAYMENT_REDIRECT`
- `FORM_REDIRECT`
- `OAUTH_REDIRECT`
- `EXTERNAL_CONFIRMATION`
- `FILE_UPLOAD_PORTAL`

其中支付只是 `PAYMENT_REDIRECT` 的一个配置实例。

## 7. 运行时主流程

### 7.1 发起 external interaction

1. assistant 在执行中识别需要站外动作
2. worker / activity 生成一个 external interaction request
3. API / runtime service 创建 `ExternalInteractionTask`
4. workflow 进入等待态，checkpoint 写入 `interactionTaskId`
5. session 追加一条带 `InteractionCardPayload` 的消息
6. 前端渲染卡片，用户点击按钮后打开外部链接

### 7.2 用户回跳应用

1. 第三方跳回约定的应用地址
2. web 端识别 query / hash 中的 `interactionTaskId`、`returnToken`
3. `App.vue` 切换到 `runtime` 或 `workflow`
4. 前端调用 interaction task 查询接口
5. 如果后端未确认最终结果，则前端显示“正在确认结果”

### 7.3 后端确认结果

后端确认优先级建议如下：

1. webhook
2. provider query
3. 内部人工回填

确认后：

1. 更新 `ExternalInteractionTask.status`
2. 写入标准化 `ExternalInteractionResult`
3. 恢复 workflow
4. session 自动追加下一条消息
5. 前端轮询后自然看到结果

## 8. 与当前项目的对接方式

### 8.1 Contracts 层

需要在共享契约中引入以下模型：

- `ExternalInteractionType`
- `ExternalInteractionStatus`
- `ExternalInteractionTask`
- `ExternalInteractionResult`
- `InteractionCardPayload`

涉及文件：

- `packages/contracts/src/index.ts`
- `packages/contracts/openapi/control-plane.yaml`
- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`

### 8.2 Runtime DTO 层

当前 `ConversationMessageDto` 需要增加结构化载荷字段。

建议从：

- `content`

扩展为：

- `content`
- `payloadType`
- `payload`

其中：

- 纯文本消息：`payloadType = null`
- external interaction 卡片：`payloadType = EXTERNAL_INTERACTION`

同时新增：

- `ExternalInteractionTaskDto`
- `CreateExternalInteractionRequest`
- `ExternalInteractionReturnAckRequest`

涉及文件：

- `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeDtos.java`

### 8.3 Runtime Service 层

`RuntimeService` 需要新增以下职责：

1. 创建 interaction task
2. 生成关联会话消息
3. 查询 interaction task 状态
4. 处理回跳确认
5. 处理外部 webhook
6. 将结果映射为 workflow 恢复动作
7. 向 session 追加 system message

建议新增接口：

- `POST /api/runtime/interactions`
- `GET /api/runtime/interactions/{interactionId}`
- `POST /api/runtime/interactions/{interactionId}/launch`
- `POST /api/runtime/interactions/{interactionId}/return`
- `POST /api/runtime/interactions/{interactionId}/complete`
- `POST /api/runtime/interactions/webhooks/{provider}`

涉及文件：

- `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeController.java`
- `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java`

### 8.4 Workflow 层

当前 worker 里只有 `submitHumanAction(...)` 这条恢复信号。

更通用的设计应该是把恢复抽象成“外部结果恢复”，而不是只能提交人工动作。

建议引入：

- `ExternalResumeAction`
- 或扩展 `HumanAction` 为更通用的 `ResumeAction`

推荐方向：

- 不再让支付结果伪装成人工点击 `CONFIRM`
- workflow checkpoint 中明确记录 `interactionTaskId`
- 恢复时把标准化 external result 送入 workflow

涉及文件：

- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`
- `apps/worker/src/main/java/com/lynxus/worker/workflow/AssistantRunWorkflowImpl.java`
- `apps/api/src/main/java/com/lynxus/platform/runtime/AssistantRunWorkflowGateway.java`

### 8.5 Web 层

#### RuntimeConversationPage

需要支持以下渲染分支：

- 纯文本消息
- system 提示消息
- external interaction 卡片消息

卡片上建议展示：

- 标题
- 描述
- 当前状态 tag
- 主按钮 `去处理`
- 辅助文案 `返回后系统会自动确认结果`

#### App.vue

需要增加以下能力：

1. 启动时解析 `window.location.search` 或 `hash`
2. 识别 `interactionTaskId` 和回跳 token
3. 自动切换到 `runtime` 或 `workflow`
4. 调用 interaction 查询接口
5. 在确认完成后清理地址栏参数

#### API Service

新增：

- `getExternalInteractionTask`
- `ackExternalInteractionReturn`
- `launchExternalInteraction`

涉及文件：

- `apps/web/src/pages/RuntimeConversationPage.vue`
- `apps/web/src/App.vue`
- `apps/web/src/services/api.ts`
- `apps/web/src/types.ts`

## 9. 持久化设计

这是 external interaction 能否成立的关键前提。

### 9.1 当前问题

虽然已有 `task_instance` 和 `workflow_instance` 表，但当前 runtime 主投影仍主要在内存里。
如果支付发生在以下场景，单靠内存会出问题：

- 用户跳转支付后 API 重启
- webhook 晚于前端回跳到达
- 用户数分钟后才回到页面
- 需要审计 interaction 过程

### 9.2 建议新增表

建议新增：

#### `conversation_session`

保存 session 基础信息。

#### `conversation_message`

保存消息历史，包括 `payload_type` 和 `payload_json`。

#### `external_interaction_task`

保存 interaction task 主状态和 provider 信息。

#### `external_interaction_event`

保存 interaction 生命周期事件，便于审计和幂等。

事件类型包括：

- `CREATED`
- `LAUNCHED`
- `RETURNED`
- `WEBHOOK_RECEIVED`
- `STATUS_CONFIRMED`
- `WORKFLOW_RESUMED`
- `SESSION_MESSAGE_APPENDED`

### 9.3 幂等键

至少需要这些幂等键：

- provider webhook event id
- provider business reference
- interaction task id
- workflow resume request id

## 10. 支付作为一个 interaction 类型的特化

在统一模型下，支付只需要补充少量特化字段：

- `paymentOrderId`
- `amount`
- `currency`
- `paymentChannel`
- `payeeDisplayName`

支付主流程：

1. workflow 决定发起支付
2. runtime 创建 `ExternalInteractionTask(type=PAYMENT_REDIRECT)`
3. 消息区展示支付卡片
4. 用户点击跳转收银台
5. 用户支付成功或失败后回到应用
6. 后端依据 webhook / query 确认结果
7. workflow 恢复
8. system message 给出“支付成功，下一步...”或“支付失败，请重试”

## 11. 安全要求

### 11.1 回跳安全

- 回跳参数不能只包含明文 `interactionTaskId`
- 需要带一次性 token 或签名
- token 需要绑定：
  - interaction task id
  - provider reference
  - expiration

### 11.2 webhook 安全

- 校验签名
- 记录原始请求摘要
- 保证重复 webhook 幂等

### 11.3 结果可信度

前端上报“我回来了”只能更新为 `RETURNED`。
最终 `SUCCEEDED / FAILED` 只能由后端可信通道确认。

## 12. MVP 分阶段落地

### Phase 1: 先打通通用 interaction 卡片和内存态闭环

目标：

- 会话支持结构化 interaction 卡片
- workflow 能进入“等待 external interaction”
- web 能处理回跳参数
- runtime 能轮询看到 interaction 状态

范围：

- 可先不接真实支付平台
- 用 mock provider 或模拟 webhook
- interaction task 先可保留内存态，仅用于验证 UI 和 workflow 主线

产出：

- 统一 DTO 和前端卡片渲染
- interaction task 状态机
- workflow 恢复主链

### Phase 2: 落持久化和 provider webhook

目标：

- interaction task 可跨进程、跨跳转可靠存在
- webhook 能最终确认结果

范围：

- 新增 interaction 相关表
- 增加 webhook 接口和幂等处理
- session/message 持久化至少落核心投影

### Phase 3: 首个真实支付 provider 接入

目标：

- 让 `PAYMENT_REDIRECT` 成为第一个真实使用的 interaction 类型

范围：

- provider adapter
- 回跳签名校验
- 主动查单补偿
- 支付失败 / 超时 / 取消的完整分支

### Phase 4: 通用化到更多站外交互

目标：

- OAuth、外部表单、签约确认等都走同一模型

范围：

- 增加 interaction type
- 丰富卡片模板
- 增强结果映射能力

## 13. 推荐实现顺序

建议按下面的顺序推进，而不是直接接支付平台。

1. 先扩共享契约和前端消息模型
2. 再引入 `ExternalInteractionTask`
3. 再把 workflow 恢复从 `HumanAction` 泛化
4. 再补回跳上下文恢复
5. 再做持久化
6. 最后接真实支付 provider

## 14. 当前明确待办

### P0

- 给 `ConversationMessage` 增加结构化 payload 能力
- 引入 `ExternalInteractionTask` 统一对象
- 定义 interaction 状态机和共享契约
- 让 workflow 能表达“等待 external interaction”
- 让 web 支持 interaction 卡片渲染和回跳恢复

### P1

- interaction task 持久化
- conversation session/message 持久化
- webhook 接口和幂等事件表
- workflow 恢复动作通用化

### P2

- 支付 provider 接入
- 通用 adapter 机制
- SSE / WebSocket 推送替代部分轮询

## 15. 仍待确认的问题

以下问题在正式开发前需要明确：

1. `WAITING_HUMAN` 是否立即改名为更通用的 `WAITING_EXTERNAL`
2. `HumanAction` 是继续兼容还是直接抽象成 `ResumeAction`
3. 当前 runtime 持久化是先最小落库，还是直接建立完整 event log
4. web 回跳路径是走 query 参数、hash 参数，还是后续补正式 router
5. interaction card 是否允许一条消息绑定多个动作

## 16. 结论

对当前项目来说，external interaction 应该被建模为运行态一等能力，而不是消息层的局部链接字段。

更合理的边界是：

- assistant / workflow 决定何时发起 external interaction
- runtime service 负责创建和追踪 interaction task
- session message 负责以卡片形式承载用户动作
- web 负责跳转与回跳上下文恢复
- webhook / provider query 负责结果确认
- workflow 负责在结果确认后继续推进

支付只是这个能力的第一个具体场景，而不是一套单独设计。
