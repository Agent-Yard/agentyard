# AgentYard Extension 接入开发指导

本文件面向需要基于 AgentYard 平台开发独立 extension service 的开发者，给出接入边界、协议契约、运行链路与开发约束。读者不需要熟悉 AgentYard 内部代码；只需要构建一个独立部署的 HTTP 服务，按本文档实现 manifest 与若干 endpoint，再请部署方把服务注册到 AgentYard。

协议事实源是 `@agentyard/extension-protocol` 包（OpenAPI、JSON Schema、examples、contract fixtures）。本文给出"实现一个 extension service 需要落地什么"的行动级指导，不重复协议字段定义。当文档与协议事实源出现不一致时，以协议事实源为准。

## 1. Extension 在系统中的位置

AgentYard 把外部接入面收敛为两类 descriptor：

- `CHANNEL_PROVIDER`：把第三方 IM / 工单 / 客服等会话渠道接入会话运行时，承担入站事件归一化、出站投递、定时拉取等职责
- `TOOL_CONNECTOR`：把第三方业务系统的能力暴露成 Tool operation，由 AgentYard agent 推理时调用

平台不感知厂商协议细节。所有协议差异、签名、cookie、长连接、SDK 依赖都收敛在 extension service 内部。平台只通过 manifest 拿到 descriptor，再以稳定的 HTTP/SSE 协议触发运行时调用。

平台侧的运行时调用方角色固定为：

- **Channel Gateway**：channel provider 运行时调用方与 outbound frame 提供方
- **Agent Runtime**：tool connector 运行时调用方
- **Agent Control Plane API**：descriptor 聚合、Web/控制面入口、可选 credential lifecycle 调用方

Agent Control Plane API 不是 runtime invocation 中转层。Tool invoke、channel `runJob`、channel outbound frame stream 全部由对应的 runtime 角色直接调用 extension。

## 2. Extension service 形态与最小职责

一个 extension service 是一个独立 HTTP 服务，必须实现：

- `GET /extension/manifest`：返回 service-level manifest envelope
- 至少声明一个 `channelProviders` 或 `toolConnectors` descriptor

按 manifest 提供下列 endpoint 之一或多个（路径由 manifest 声明，平台按 manifest 路径调用）：

- `toolConnector.endpoints.invoke`：`TOOL_CONNECTOR` descriptor 必填
- `channelProvider.endpoints.runJob`：仅在 `CHANNEL_PROVIDER` descriptor 声明 `jobDefinitions` 时需要
- `credentialLifecycleEndpointProfiles.*.{createCredential,rotateCredential,revokeCredential}`：可选；descriptor 通过 `credentialLifecycleEndpointProfile` 引用 profile 后才被视为 `REMOTE_LIFECYCLE` credential capability
- `credentialLifecycleEndpointProfiles.*.validateCredential`：可选；未声明时平台不展示/不调用独立 credential validate 操作

可选但建议实现：

- `GET /extension/health`：诊断健康；返回 `ExtensionHealth`（`status: UP|DOWN|DEGRADED`）
- `GET /health/live`、`GET /health/ready`：用于平台/容器层探活
- 当声明 `CHANNEL_PROVIDER` 时，订阅由 AgentYard 提供的 outbound frame stream 与 ACK 接口（详见 §6）

protocol 常量与 header 名以 SDK 常量为准：

- JVM：`com.agentyard.extension.sdk.protocol.AgentYardExtensionProtocol`、`AgentYardExtensionHeaders`
- Python：`agentyard_extension_sdk.protocol`

不要硬编码字符串路径或 header 名；应直接使用 SDK 常量与帮助函数（如 `build_manifest_url`、`build_service_level_headers`、`build_descriptor_level_headers`、`credential_lifecycle_headers`）。

## 3. Manifest 契约

Manifest envelope 字段与约束（详见 `service-manifest.schema.json`）：

- `extensionApiVersion`：`1`
- `coreMinVersion` / `coreMaxVersion`：声明该 extension 兼容的 AgentYard 版本范围
- `descriptors.channelProviders` 与 `descriptors.toolConnectors`：两个键都必须存在；至少一个数组非空

每个 descriptor 必须自洽，即 schema、UI schema、runtime endpoints 与可选 credential lifecycle profile 引用都通过 manifest 一次性声明。SDK 提供 manifest 校验器（JVM `ManifestValidator.validate`、Python `validate_manifest_against_protocol_schema`），平台在拉取 manifest 时也会再次跑同一校验；任何 schema 错误都会让该服务下所有 descriptor 进入 NOT_READY，不会暴露给 Web 或 runtime。

约束要点：

- `defaultConfig`、`defaultSchedule.jobConfig` 不允许包含敏感字段。平台会拒绝键名为 `externalSecretRef` / `password` / `apiKey` / `accessToken` / `refreshToken` / `privateKey` / `webhookSigningSecret`，或对应 UI schema 中 `secret: true` 的默认值。敏感字段必须放进 credential schema，不得作为普通配置默认值
- `accountConfigSchema` / `configSchema` / `operationMappingSchema` / `credentialSchema` 都是普通 JSON Schema 对象。Web 渲染依赖配套的 `*UiSchema`（结构见 `ui-field.schema.json`）
- `outbound.mode` 当前只有 `FRAME_STREAM`；`supportsFinalDelivery` 与 `requiresIdempotentFinalDelivery` 都必须为 `true`。`supportsTyping` / `supportsDraftUpdate` 由 extension 自行声明，平台在生成 frame 时会按声明过滤
- descriptor `endpoints` 与 `credentialLifecycleEndpointProfiles` 中所有 declared path 必须以 `/` 开头且不含 query/fragment（pattern `^/[^?#]*$`）。平台调用时以 `baseUrl + path` 拼接

完整样例见 `@agentyard/extension-protocol` 包的 `examples/service-manifest.enterprise-service.json`。

### Credential capability 识别规则

平台按以下规则推导 credential 能力：

- descriptor 未声明 `credentialSchema`：`enabled = false`
- 声明了 `credentialSchema` 且 `credentialLifecycleEndpointProfile` 指向一个包含 `createCredential`、`rotateCredential`、`revokeCredential` 的 profile：`mode = REMOTE_LIFECYCLE`，平台通过这些 endpoint 完成 create/rotate/revoke
- 同一 profile 声明 `validateCredential`：`supportsValidate = true`，平台允许独立 validate；未声明则 `supportsValidate = false`，平台隐藏 validate 操作，直接调用会返回 unsupported
- 声明了 `credentialSchema` 但未引用有效 profile：对外部 extension 视为 `enabled = false`

因此 extension 若需要 credential lifecycle，必须在 manifest 中同时声明 `credentialSchema`、`credentialUiSchema` 和一个包含 create/rotate/revoke 的 credential lifecycle endpoint profile。`validateCredential` 只在第三方系统能可靠探测凭证时声明；否则应留空，让真正的 tool/channel runtime 调用暴露连通性问题。

## 4. Static registration

Extension service 必须由部署方在 AgentYard 平台的 registration YAML 中声明（路径由部署侧 `AGENTYARD_EXTENSION_REGISTRATION_FILE` 环境变量指定）。Extension 开发者需要把以下信息提供给部署方：

- `registrationId`：服务的稳定标识，例如 `acme-business-connectors`
- `baseUrl`：extension service 的可达 HTTP(s) 地址
- `exposes.channelProviderTypes` / `exposes.toolConnectorTypes`：本服务在 manifest 中声明的所有 descriptor type

样例：

```yaml
agentyard:
  extensions:
    services:
      - registrationId: acme-business-connectors
        baseUrl: http://127.0.0.1:18100
        exposes:
          channelProviderTypes: []
          toolConnectorTypes:
            - enterprise.acme.crm
        auth:
          type: INTERNAL_TOKEN

      - registrationId: acme-channel-provider
        baseUrl: http://127.0.0.1:18101/agentyard
        exposes:
          channelProviderTypes:
            - enterprise.acme.internal-im
          toolConnectorTypes: []
        auth:
          type: INTERNAL_TOKEN
```

约束：

- `registrationId` 不能以 `core-` 开头；该前缀保留给 AgentYard 自身的内置服务
- `baseUrl` 必须是 http(s)，禁止 userinfo / query / fragment；可整体使用 `${ENV_NAME}` 占位符
- `exposes` 必须列举该服务声明的 `channelProviderTypes` / `toolConnectorTypes`；任何只在 manifest 出现但未在 `exposes` 中声明的 descriptor 会被标记 `UNEXPECTED_DESCRIPTOR`，反之则 `MISSING_DESCRIPTOR`，整组 registry 都会进入 NOT_READY
- `auth.type` 当前只支持 `INTERNAL_TOKEN`

## 5. 协议头与认证

平台调用 extension 的所有请求都会携带 `Authorization: Bearer <token>`。Extension service 必须校验该 token；token 由部署方在平台与 extension 两侧通过同一份 secret 配置（约定环境变量名 `AGENTYARD_INTERNAL_AUTH_TOKEN`）。SDK 当前未内置 server filter，extension 项目根据自身 web 框架实现校验即可。

请求头分级：

| 调用类型 | 必填头 |
|----------|--------|
| Service-level（GET `/extension/manifest`、`/extension/health`） | `Authorization`，可选 `X-AgentYard-Extension-Registration-Id` |
| Descriptor-level（tool invoke、runJob、outbound 订阅/流/ACK） | `Authorization`、`X-AgentYard-Extension-Registration-Id`、`X-AgentYard-Extension-Descriptor-Type`（`TOOL_CONNECTOR` 或 `CHANNEL_PROVIDER`）、`X-AgentYard-Extension-Descriptor-Id`、`X-AgentYard-Trace-Id`、`X-AgentYard-Request-Id`、`Idempotency-Key` |
| Credential lifecycle | `Authorization`、`X-AgentYard-Trace-Id`、`X-AgentYard-Request-Id` |

`Idempotency-Key` 必须满足 `^[A-Za-z0-9._:-]+$`，最长 128。同一业务请求的重放使用同一 `Idempotency-Key`；extension 必须按这个键去重，多次重放只产生一次副作用。

## 6. Channel Provider 运行链路

Channel provider 同时是 inbound 来源、outbound frame 消费方、可选 job 执行方。

### 6.1 入站事件归一化

Extension 把外部渠道事件归一化为 `NormalizedChannelInboundEvent`（schema 见 OpenAPI），通过 `POST <agentyard-base>/internal/channel-events/normalized` 提交给平台。基址由部署方告知 extension（一般是 AgentYard channel gateway 服务的地址）。

要点：

- 必填字段：`providerType`、`channelProfileId`、`eventType`、`dedupKey`、`normalizedPayload`、`traceContext`
- `dedupKey` 是 idempotency key，平台基于它对相同事件去重
- 不要在 `rawPayload` 中泄漏凭证；`rawPayload` 仅用于诊断
- `eventType` 限制为 OpenAPI 中的枚举值；自定义事件应映射到 `UNKNOWN` 并通过 `metadata` 附加上下文

### 6.2 出站 frame stream（extension 是消费方）

平台对 extension 暴露：

- `GET /extension/channel/outbound-frame-subscriptions`：bootstrap subscription snapshot
- `GET /extension/channel/outbound-frames/stream?channelProfileId=...`：SSE 流，event 名 `channel-outbound-frame`，data 为 `ChannelOutboundFrame`
- `POST /extension/channel/outbound-frames/ack`：仅对 `FINAL_DELIVERY` ACK

Extension 在启动时：

1. 调用订阅快照接口拉取 ACTIVE channel profile 列表
2. 对每个 `streamUrl` 打开 SSE，按事件 kind 处理 frame
3. 收到 `FINAL_DELIVERY` 时，按 `frameId` + `idempotencyKey` 做 provider 端幂等投递；成功后回 ACK

约束：

- `frameId` 是 ACK / checkpoint 身份；同一业务消息的重放使用同一 `frameId`
- `idempotencyKey` 长度 ≤ 50，是供 provider 投递接口使用的紧凑键，可用于 provider 内部 dedup
- `finalSequence` 仅出现在 `FINAL_DELIVERY`，是全局可持久化的最终投递顺序检查点；Last-Event-ID（streamCursor）只服务于 SSE 短期重放，不能替代 `finalSequence`
- 非 FINAL_DELIVERY frame（`TYPING_*`、`DRAFT_*`）不需要也不允许 ACK
- ACK metadata 禁止携带 `providerResponse` / `rawProviderResponse` / `credential` / `externalSecretRef`，仅放可脱敏的诊断信息
- 当 provider 不支持 typing / draft 时，应在 manifest 中声明 `supportsTyping: false` 等，平台会按声明过滤；extension 仍应对未声明支持的 frame 做 no-op 处理而不是报错
- ACK 必须按 `finalSequence` 递增顺序提交；顺序错乱时平台会返回 409，extension 应基于响应中的 `lastAckedFinalSequence` 重新对齐再继续

### 6.3 Provider job

声明 `jobDefinitions` 后，平台按 `defaultSchedule` 或 admin 配置触发 `POST <runJobPath>`。请求体 `ChannelRunJobRequest` 含：

- `providerType`、`channelProfileId`、`config`、可选 `externalSecretRef`、`idempotencyKey`、`traceContext`
- `payload.jobType` / `payload.jobConfig`、可选 `scheduledAt`

响应 `ChannelRunJobResponse`：

- `status`：`SUCCEEDED` 或 `NOOP`
- `events`：可选 `NormalizedChannelInboundEvent` 列表（用于 pull-style provider 直接把拉到的消息附在响应中）
- `nextCursor`：下次 pull 起点，由 extension 维护语义
- `metadata`：可附加诊断

`scheduleType` 当前支持 `INTERVAL`、`CRON`、`MANUAL`（见 `schedule-config.schema.json`）；`timezone` 必填。

## 7. Tool Connector 运行链路

AgentYard agent runtime 在执行推理时，对每个 Tool 调用按 `connectorType` 路由到 manifest 声明的 `endpoints.invoke`。

请求体 `RemoteToolInvokeRequest` 字段：

- `connectorType`：descriptor `connectorType`
- 可选 `externalSecretRef`：仅当对应 Integration Account 已配置且 credential 已 ACTIVE 时由平台注入
- `tool.{resourceId, resourceVersionId, name}`：平台持有的 tool 标识
- `operation.{name, description}`：被调用的 Tool operation
- `config.connector` / `config.operationMapping`：来自 Tool 资源版本的冻结配置
- `input.arguments`：已通过 Tool input schema 校验的参数
- `execution.{idempotencyKey, timeoutSeconds, traceContext}`

响应 `ToolInvokeResponse`：

- `status: "SUCCEEDED"`（仅这一个值）
- `output`：JSON object，必须满足 Tool 的 output schema
- `metadata`：JSON object，调用方可读但不参与业务结果

业务失败应返回非 2xx + `ExtensionError`，不要在 200 响应中夹带错误。

## 8. Credential lifecycle

Credential lifecycle 是 control-plane 操作，由 AgentYard 控制面在 Web/admin 触发 Integration Account 操作时调用：

- `POST createCredentialPath`：`CreateCredentialRequest` → `CreateCredentialResponse`，必返回 `externalSecretRef` 和 `credentialStatus: ACTIVE`
- `POST rotateCredentialPath`：`RotateCredentialRequest`，extension 应原地写入新的密钥并返回新的 `externalSecretRef`
- `POST validateCredentialPath`：可选；返回 `ACTIVE` / `VALIDATION_FAILED` / `ROTATION_REQUIRED`
- `POST revokeCredentialPath`：返回 `credentialStatus: REVOKED`

约束：

- 平台永不收到明文凭证。`credential` 字段在请求体中由 Web 直传 extension（经平台路由），extension 自行加密落到自有 vault，并仅返回 opaque `externalSecretRef`
- 对未声明 `validateCredential` 的远程 lifecycle，`ACTIVE` 仅表示 extension 已接收并保存凭证引用，可用于 runtime；不表示第三方系统已被独立探测通过
- `externalSecretRef` 长度 ≤ 512，blank 视为无效
- runtime 调用（tool invoke、runJob）只接收 `externalSecretRef`；extension 自行解析并取回真实密钥。runtime 路径上的平台服务从不持有明文凭证

## 9. ExtensionError 契约

所有非 2xx 响应必须返回 `ExtensionError`（`extension-error.schema.json`）：

```json
{
  "errorCode": "REMOTE_TIMEOUT",
  "message": "...",
  "category": "REMOTE_TIMEOUT",
  "retryable": true,
  "details": {}
}
```

约束：

- `category` 取 `AUTH` / `BAD_REQUEST` / `REMOTE_TIMEOUT` / `REMOTE_UNAVAILABLE` / `REMOTE_RATE_LIMITED` / `REMOTE_BUSINESS_REJECTED` / `PROTOCOL_ERROR` / `UNKNOWN`
- `CIRCUIT_OPEN` 是平台保留的内部熔断语义，extension 不要返回；平台收到该 category 时会强制改写为 `PROTOCOL_ERROR`
- `details` 必须存在且为对象，不携带凭证或外部敏感字段
- 5xx 必须配合 `retryable: true` 才会被平台重试；`PROTOCOL_ERROR` 永不重试

平台对 retryable 错误内部做有限 backoff retry 与熔断保护。Extension 不应假设平台会无限重试，也不应依赖平台特定的重试间隔；自身需要的重试与回退应在 extension 内部完成。

## 10. 安全与隐私

- Manifest 与 descriptor 是公开声明，禁止包含密钥、签名材料、凭证 default
- Tool / channel runtime 调用从不携带明文凭证；只有 `externalSecretRef`
- Extension 自有日志中也应避免打印 `Authorization`、`externalSecretRef`、用户 PII；ACK metadata 同样禁止携带 raw provider response
- Extension 服务必须实现 `Authorization: Bearer` 校验；token 通过部署方下发的 secret 管理
- 不要在 `rawPayload` 中携带凭证或解密后的密钥
- Extension 自身的密钥/凭证管理应使用企业自有 vault，不要回写到平台任何持久化对象

## 11. SDK 与开发约束

JVM 与 Python 都提供 SDK（仅 hand-written 协议常量、validator、registration loader、canonical JSON 等公共件）：

- JVM：`packages/extension-sdk-jvm`，关键入口：
  - `AgentYardExtensionProtocol`、`AgentYardExtensionHeaders`：协议常量
  - `ManifestValidator` / `ManifestValidationResult`：manifest schema 验证
  - `ExtensionRegistrationLoader`：YAML 解析与 baseUrl 归一化（部署方使用）
  - `AgentYardCanonicalJson`、`DescriptorDefinitionDigests`：稳定摘要
  - `AgentYardExtensionHttp`：URL 拼接与 header 构造帮助
- Python：`packages/extension-sdk-python`，`agentyard_extension_sdk.protocol` 暴露同名常量与帮助函数；`agentyard_extension_sdk.validation.manifest`、`agentyard_extension_sdk.tool` / `channel`、`agentyard_extension_sdk.common.canonical_json` 等

约束：

- 所有生成的 DTO / client / server stub 源码必须写到 `build/generated/*` 目录，不允许提交到 SDK 源码目录或 extension 项目源码目录；JVM SDK 发布物会包含编译后的 generated protocol DTO class，供外部 extension 项目直接引用
- 不要在 extension 项目里复制粘贴协议常量；统一引 SDK
- 不要绕过 `ManifestValidator`：平台在加载 manifest 时会再次跑一次完整校验，本地通过验证后再发布

外部开发者应从独立维护的 `agentyard-extension-boilerplate` 项目复制 Java 21 + Spring Boot 模板作为新 extension service 起点。该模板不再位于 AgentYard monorepo 内；它作为独立 Gradle 项目依赖已发布的 `com.agentyard:extension-sdk-jvm`，并包含 manifest、tool invoke、channel runJob、credential lifecycle、channel outbound/inbound 客户端占位。

## 12. Descriptor 字段分层

实现一个 channel provider / tool connector 时，按 descriptor 边界把字段切清：

- `accountConfigSchema` / `accountConfigUiSchema`：Integration Account 上承载的账号级身份（如 tenantId、appId）
- `credentialSchema` / `credentialUiSchema`：仅密钥；`secret: true` 字段会被 Web 当 password 渲染并禁止落 `defaultConfig`
- `credentialLifecycleEndpointProfile`：descriptor 对 manifest 级 credential lifecycle endpoint profile 的引用；多个 descriptor 可复用同一组 endpoint path
- `configSchema` / `configUiSchema` / `defaultConfig`：channel profile / tool resource 级运行配置
- `operationMappingSchema` / `operationMappingUiSchema`：tool connector 每个 operation 的协议映射（如 HTTP method/path、远端 tool 名）
- `jobConfigSchema` / `jobConfigUiSchema` 与 `defaultSchedule`：channel provider job 的配置和默认排程

字段分层清晰后，平台能基于 manifest 自动渲染 Web 配置表单、自动注入 credential 与配置，extension 项目只需要专注于自家协议实现。

## 13. 验证与自检

Extension service 在交付前应至少完成：

- 协议事实源 self-check：

  ```bash
  pnpm --filter @agentyard/extension-protocol self-check
  ```

- 用 SDK 的 manifest validator 跑自家 manifest（JVM `ManifestValidator.validate`、Python `validate_manifest_against_protocol_schema`）；任何 `errors` 非空都不应启动服务
- 端到端：将 service `baseUrl` 写入平台的 registration YAML，启动后通过平台的 extension 聚合校验接口确认下列错误码均为空：
  - `MANIFEST_FETCH_FAILED`
  - `MANIFEST_SCHEMA_INVALID`
  - `MISSING_DESCRIPTOR`
  - `UNEXPECTED_DESCRIPTOR`
  - `DUPLICATE_DESCRIPTOR`
  - `CONFIG_SECRET_MATERIAL_NOT_ALLOWED`
- ACK / SSE 行为：channel provider extension 应模拟 SSE 断流重连、`FINAL_DELIVERY` 重放、ACK 顺序与去重；ACK 顺序错乱时平台会返回 409，extension 必须能从响应中的 `lastAckedFinalSequence` 恢复

## 14. 协议演进与版本

`@agentyard/extension-protocol` 是版本化协议；当前 `extensionApiVersion = 1`。AgentYard 不承诺在协议版本之间向后兼容，也不会为单个 extension 维护双协议并存或字段宽容默认值。

Extension 项目的应对方式：

- 在 manifest 的 `coreMinVersion` / `coreMaxVersion` 中声明实际兼容的 AgentYard 版本范围
- 升级 AgentYard 时同步升级 `@agentyard/extension-protocol` 与对应 SDK 版本，按新版协议事实源回归本文 §13 的全部校验项
- 协议字段层面的不兼容变更（新增/删除字段、收紧约束）随 `extensionApiVersion` 主版本变更披露；extension 项目必须在主版本变更后重新发布

需要 AgentYard 平台扩展协议本身（新增 endpoint、新增 descriptor 类型、改变 frame 语义等）时，请通过平台维护方提出协议变更请求，不要在 extension 端通过 manifest 自定义字段绕过。
